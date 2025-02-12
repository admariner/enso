import io.circe.yaml
import io.circe.syntax._
import org.apache.commons.io.IOUtils
import sbt.internal.util.ManagedLogger
import sbt._
import sbt.io.syntax.fileToRichFile
import sbt.util.{CacheStore, CacheStoreFactory, FileInfo, Tracked}

import scala.sys.process._
import org.enso.build.WithDebugCommand

import java.nio.file.Paths
import scala.util.Try

object DistributionPackage {

  /** File extensions. */
  implicit class FileExtensions(file: File) {

    /** Get the outermost directory of this file. For absolute paths this
      * function always returns root.
      *
      * == Example ==
      * Get top directory of the relative path.
      * {{{
      *   file("foo/bar/baz").getTopDirectory == file("foo")
      * }}}
      *
      * Get top directory of the absolute path.
      * {{{
      *   file(/foo/bar/baz").getTopDirectory == file("/")
      * }}}
      *
      * @return the outermost directory of this file.
      */
    def getTopDirectory: File = {
      @scala.annotation.tailrec
      def go(path: File): File = {
        val parent = path.getParentFile
        if (parent == null) path else go(parent)
      }
      go(file)
    }
  }

  /** Conditional copying, based on the contents of cache and timestamps of files.
    *
    * @param source source directory
    * @param destination target directory
    * @param cache cache used for persisting the cached information
    * @return true, if copying was necessary, false if no change was detected between the directories
    */
  def copyDirectoryIncremental(
    source: File,
    destination: File,
    cache: CacheStore
  ): Boolean = {
    val allFiles = source.allPaths.get().toSet
    Tracked.diffInputs(cache, FileInfo.lastModified)(allFiles) { diff =>
      val missing = diff.unmodified.exists { f =>
        val relativePath = f.relativeTo(source).get
        val destinationFile =
          destination.toPath.resolve(relativePath.toPath).toFile
        !destinationFile.exists()
      }

      if (diff.modified.nonEmpty || diff.removed.nonEmpty || missing) {
        IO.delete(destination)
        IO.copyDirectory(source, destination)
        true
      } else false
    }
  }

  def copyFilesIncremental(
    sources: Seq[File],
    destinationDirectory: File,
    cache: CacheStore
  ): Unit = {
    val allFiles = sources.toSet
    IO.createDirectory(destinationDirectory)
    Tracked.diffInputs(cache, FileInfo.lastModified)(allFiles) { diff =>
      for (f <- diff.removed) {
        IO.delete(destinationDirectory / f.getName)
      }
      for (f <- diff.modified -- diff.removed) {
        IO.copyFile(f, destinationDirectory / f.getName)
      }
      for (f <- diff.unmodified) {
        val destinationFile = destinationDirectory / f.getName
        if (!destinationFile.exists()) {
          IO.copyFile(f, destinationDirectory / f.getName)
        }
      }
    }
  }

  private def executableName(baseName: String): String =
    if (Platform.isWindows) baseName + ".exe" else baseName

  private def batOrExeName(baseName: String): String =
    if (Platform.isWindows) {
      if (GraalVM.EnsoLauncher.native) {
        baseName + ".exe"
      } else {
        baseName + ".bat"
      }
    } else {
      baseName
    }

  def createProjectManagerPackage(
    distributionRoot: File,
    cacheFactory: CacheStoreFactory
  ): Unit = {
    copyDirectoryIncremental(
      file("distribution/project-manager/THIRD-PARTY"),
      distributionRoot / "THIRD-PARTY",
      cacheFactory.make("project-manager-third-party")
    )

    copyFilesIncremental(
      Seq(file(executableName("project-manager"))),
      distributionRoot / "bin",
      cacheFactory.make("project-manager-exe")
    )
  }

  def createEnginePackage(
    distributionRoot: File,
    cacheFactory: CacheStoreFactory,
    log: Logger,
    jarModulesToCopy: Seq[File],
    graalVersion: String,
    javaVersion: String,
    ensoVersion: String,
    editionName: String,
    sourceStdlibVersion: String,
    targetStdlibVersion: String,
    targetDir: File
  ): Unit = {
    copyDirectoryIncremental(
      file("distribution/engine/THIRD-PARTY"),
      distributionRoot / "THIRD-PARTY",
      cacheFactory.make("engine-third-party")
    )

    copyFilesIncremental(
      jarModulesToCopy,
      distributionRoot / "component",
      cacheFactory.make("module jars")
    )

    val parser = targetDir / Platform.dynamicLibraryFileName("enso_parser")
    copyFilesIncremental(
      Seq(parser),
      distributionRoot / "component",
      cacheFactory.make("engine-parser-library")
    )

    (distributionRoot / "editions").mkdirs()
    Editions.writeEditionConfig(
      editionsRoot   = distributionRoot / "editions",
      ensoVersion    = ensoVersion,
      editionName    = editionName,
      libraryVersion = targetStdlibVersion,
      log            = log
    )

    copyLibraryCacheIncremental(
      sourceRoot      = file("distribution/lib"),
      destinationRoot = distributionRoot / "lib",
      sourceVersion   = sourceStdlibVersion,
      targetVersion   = targetStdlibVersion,
      cacheFactory    = cacheFactory.sub("engine-libraries"),
      log             = log
    )

    if (!GraalVM.EnsoLauncher.shell) {
      log.info(
        s"Not using shell launchers as ${GraalVM.EnsoLauncher.VAR_NAME} env variable is ${GraalVM.EnsoLauncher.toString}"
      )
    } else {
      copyDirectoryIncremental(
        file("distribution/bin"),
        distributionRoot / "bin",
        cacheFactory.make("engine-bin")
      )
    }

    buildEngineManifest(
      template     = file("distribution/manifest.template.yaml"),
      destination  = distributionRoot / "manifest.yaml",
      graalVersion = graalVersion,
      javaVersion  = javaVersion
    )
  }

  def indexStdLibs(
    stdLibVersion: String,
    ensoVersion: String,
    stdLibRoot: File,
    ensoExecutable: File,
    cacheFactory: CacheStoreFactory,
    log: Logger
  ): Unit = {
    for {
      libMajor <- stdLibRoot.listFiles()
      libName  <- (stdLibRoot / libMajor.getName).listFiles()
    } yield {
      indexStdLib(
        libName,
        stdLibVersion,
        ensoVersion,
        ensoExecutable,
        cacheFactory,
        log
      )
    }
  }

  def indexStdLib(
    libName: File,
    stdLibVersion: String,
    ensoVersion: String,
    ensoExecutable: File,
    cacheFactory: CacheStoreFactory,
    log: Logger
  ): Unit = {
    object FileOnlyFilter extends sbt.io.FileFilter {
      def accept(arg: File): Boolean = arg.isFile
    }
    val cache = cacheFactory.make(s"$libName.$ensoVersion")
    val path  = libName / ensoVersion
    Tracked.diffInputs(cache, FileInfo.lastModified)(
      path.globRecursive("*.enso" && FileOnlyFilter).get().toSet
    ) { diff =>
      if (diff.modified.nonEmpty) {
        log.info(s"Generating index for $libName ")
        val fileToExecute = new File(
          ensoExecutable.getParentFile,
          batOrExeName(ensoExecutable.getName)
        )

        def assertExecutable(when: String) = {
          if (!fileToExecute.canExecute()) {
            log.warn(s"Not an executable file ${fileToExecute} $when")
            var dir = fileToExecute
            while (dir != null && !dir.exists()) {
              dir = dir.getParentFile
            }
            var count = 0
            if (dir != null) {
              log.warn(s"Content of ${dir}")
              Option(dir.listFiles).map(_.map { file =>
                log.warn(s"  ${file}")
                count += 1
              })
            }
            log.warn(s"Found ${count} files.")
          }
        }
        assertExecutable("before launching")
        val command = Seq(
          fileToExecute.getAbsolutePath,
          "--no-compile-dependencies",
          "--no-global-cache",
          "--compile",
          path.getAbsolutePath
        )
        log.debug(command.mkString(" "))
        try {
          val runningProcess = Process(
            command,
            Some(path.getAbsoluteFile.getParentFile),
            "JAVA_OPTS" -> "-Dorg.jline.terminal.dumb=true"
          ).run
          // Poor man's solution to stuck index generation
          val GENERATING_INDEX_TIMEOUT = 60 * 2 // 2 minutes
          var current                  = 0
          var timeout                  = false
          while (runningProcess.isAlive() && !timeout) {
            if (current > GENERATING_INDEX_TIMEOUT) {
              java.lang.System.err
                .println(
                  "Reached timeout when generating index. Terminating..."
                )
              try {
                val pidOfProcess = pid(runningProcess)
                val javaHome     = System.getProperty("java.home")
                val jstack =
                  if (javaHome == null) "jstack"
                  else
                    Paths.get(javaHome, "bin", "jstack").toAbsolutePath.toString
                val in = java.lang.Runtime.getRuntime
                  .exec(Array(jstack, pidOfProcess.toString))
                  .getInputStream

                System.err.println(IOUtils.toString(in, "UTF-8"))
              } catch {
                case e: Throwable =>
                  java.lang.System.err
                    .println("Failed to get threaddump of a stuck process", e);
              } finally {
                timeout = true
                runningProcess.destroy()
              }
            } else {
              Thread.sleep(1000)
              current += 1
            }
          }
          if (timeout) {
            throw new RuntimeException(
              s"TIMEOUT: Failed to compile $libName in $GENERATING_INDEX_TIMEOUT seconds"
            )
          }
          if (runningProcess.exitValue() != 0) {
            throw new RuntimeException(s"Cannot compile $libName.")
          }
        } finally {
          assertExecutable("after execution")
        }
      } else {
        log.debug(s"No modified files. Not generating index for $libName.")
      }
    }
  }

  def runEnginePackage(
    distributionRoot: File,
    args: Seq[String],
    log: Logger
  ): Boolean = {
    import scala.collection.JavaConverters._

    val enso             = distributionRoot / "bin" / batOrExeName("enso")
    val pb               = new java.lang.ProcessBuilder()
    val all              = new java.util.ArrayList[String]()
    val runArgumentIndex = locateRunArgument(args)
    val runArgument      = runArgumentIndex.map(args)
    val disablePrivateCheck = runArgument match {
      case Some(whatToRun) =>
        if (whatToRun.startsWith("test/") && whatToRun.endsWith("_Tests")) {
          whatToRun.contains("_Internal_")
        } else {
          false
        }
      case None => false
    }

    val runArgumentAsFile = runArgument.flatMap(createFileIfValidPath)
    val projectDirectory  = runArgumentAsFile.flatMap(findProjectRoot)
    val cwdOverride: Option[File] =
      projectDirectory.flatMap(findParentFile).map(_.getAbsoluteFile)

    all.add(enso.getAbsolutePath)
    all.addAll(args.asJava)
    // Override the working directory of new process to be the parent of the project directory.
    cwdOverride.foreach { c =>
      pb.directory(c)
    }
    if (cwdOverride.isDefined) {
      // If the working directory is changed, we need to translate the path - make it absolute.
      all.set(runArgumentIndex.get + 1, runArgumentAsFile.get.getAbsolutePath)
    }
    if (args.contains("--debug")) {
      all.remove("--debug")
      pb.environment().put("JAVA_OPTS", "-ea " + WithDebugCommand.DEBUG_OPTION)
    } else {
      pb.environment().put("JAVA_OPTS", "-ea")
    }
    if (disablePrivateCheck) {
      all.add("--disable-private-check")
    }
    pb.command(all)
    pb.inheritIO()
    log.info(s"Executing ${all.asScala.mkString(" ")}")
    val p        = pb.start()
    val exitCode = p.waitFor()
    if (exitCode != 0) {
      log.warn(enso + " finished with exit code " + exitCode)
    }
    exitCode == 0
  }

  // https://stackoverflow.com/questions/23279898/get-process-id-of-scala-sys-process-process
  def pid(p: Process): Long = {
    val procField = p.getClass.getDeclaredField("p")
    procField.synchronized {
      procField.setAccessible(true)
      val proc = procField.get(p)
      try {
        proc match {
          case unixProc
              if unixProc.getClass.getName == "java.lang.UNIXProcess" =>
            val pidField = unixProc.getClass.getDeclaredField("pid")
            pidField.synchronized {
              pidField.setAccessible(true)
              try {
                pidField.getLong(unixProc)
              } finally {
                pidField.setAccessible(false)
              }
            }
          case javaProc: java.lang.Process =>
            javaProc.pid()
          case other =>
            throw new RuntimeException(
              "Cannot get PID of a " + proc.getClass.getName
            )
        }
      } finally {
        procField.setAccessible(false)
      }
    }
  }

  /** Returns the index of the next argument after `--run`, if it exists. */
  private def locateRunArgument(args: Seq[String]): Option[Int] = {
    val findRun = args.indexOf("--run")
    if (findRun >= 0 && findRun + 1 < args.size) {
      Some(findRun + 1)
    } else {
      None
    }
  }

  /** Returns a file, only if the provided string represented a valid path. */
  private def createFileIfValidPath(path: String): Option[File] =
    Try(new File(path)).toOption

  /** Looks for a parent directory that contains `package.yaml`. */
  private def findProjectRoot(file: File): Option[File] =
    if (file.isDirectory && (file / "package.yaml").exists()) {
      Some(file)
    } else {
      findParentFile(file).flatMap(findProjectRoot)
    }

  private def findParentFile(file: File): Option[File] =
    Option(file.getParentFile)

  def runProjectManagerPackage(
    engineRoot: File,
    distributionRoot: File,
    args: Seq[String],
    log: Logger
  ): Boolean = {
    import scala.collection.JavaConverters._

    val enso = distributionRoot / "bin" / "project-manager"
    log.info(s"Executing $enso ${args.mkString(" ")}")
    val pb  = new java.lang.ProcessBuilder()
    val all = new java.util.ArrayList[String]()
    all.add(enso.getAbsolutePath())
    all.addAll(args.asJava)
    pb.command(all)
    pb.environment().put("ENSO_ENGINE_PATH", engineRoot.toString())
    pb.environment().put("ENSO_JVM_PATH", System.getProperty("java.home"))
    if (args.contains("--debug")) {
      all.remove("--debug")
      pb.environment().put("ENSO_JVM_OPTS", WithDebugCommand.DEBUG_OPTION)
    }
    pb.inheritIO()
    val p        = pb.start()
    val exitCode = p.waitFor()
    if (exitCode != 0) {
      log.warn(enso + " finished with exit code " + exitCode)
    }
    exitCode == 0
  }

  def fixLibraryManifest(
    packageRoot: File,
    targetVersion: String,
    log: Logger
  ): Unit = {
    val packageConfig   = packageRoot / "package.yaml"
    val originalContent = IO.read(packageConfig)
    yaml.parser.parse(originalContent) match {
      case Left(error) =>
        log.error(s"Failed to parse $packageConfig: $error")
        throw error
      case Right(parsed) =>
        val obj = parsed.asObject.getOrElse {
          throw new IllegalStateException(s"Incorrect format of $packageConfig")
        }

        val key        = "version"
        val updated    = obj.remove(key).add(key, targetVersion.asJson)
        val serialized = yaml.printer.print(updated.asJson)
        if (serialized == originalContent) {
          log.info(
            s"No need to update $packageConfig, already in correct version."
          )
        } else {
          IO.write(packageConfig, serialized)
          log.debug(s"Updated $packageConfig to $targetVersion")
        }
    }
  }

  def copyLibraryCacheIncremental(
    sourceRoot: File,
    destinationRoot: File,
    sourceVersion: String,
    targetVersion: String,
    cacheFactory: CacheStoreFactory,
    log: Logger
  ): Unit = {
    val existingLibraries =
      collection.mutable.ArrayBuffer.empty[(String, String)]
    for (prefix <- sourceRoot.list()) {
      for (libName <- (sourceRoot / prefix).list()) {
        val targetPackageRoot =
          destinationRoot / prefix / libName / targetVersion
        copyDirectoryIncremental(
          source      = sourceRoot / prefix / libName / sourceVersion,
          destination = targetPackageRoot,
          cache       = cacheFactory.make(s"$prefix.$libName")
        )
        fixLibraryManifest(targetPackageRoot, targetVersion, log)
        existingLibraries.append((prefix, libName))
      }
    }

    val existingLibrariesSet = existingLibraries.toSet
    for (prefix <- destinationRoot.list()) {
      for (libName <- (destinationRoot / prefix).list()) {
        if (!existingLibrariesSet.contains((prefix, libName))) {
          log.info(
            s"Removing a library $prefix.$libName from the distribution, " +
            s"because it does not exist in the sources anymore."
          )
        }
      }
    }

  }

  private def buildEngineManifest(
    template: File,
    destination: File,
    graalVersion: String,
    javaVersion: String
  ): Unit = {
    val base = IO.read(template)
    val extensions =
      s"""graal-vm-version: $graalVersion
         |graal-java-version: $javaVersion
         |""".stripMargin
    IO.write(destination, base + extensions)
  }

  def createLauncherPackage(
    distributionRoot: File,
    cacheFactory: CacheStoreFactory
  ): Unit = {
    copyDirectoryIncremental(
      file("distribution/launcher/THIRD-PARTY"),
      distributionRoot / "THIRD-PARTY",
      cacheFactory.make("launcher-third-party")
    )

    copyFilesIncremental(
      Seq(file(executableName("ensoup"))),
      distributionRoot / "bin",
      cacheFactory.make("launcher-exe")
    )

    IO.createDirectory(distributionRoot / "dist")
    IO.createDirectory(distributionRoot / "runtime")

    copyFilesIncremental(
      Seq(
        file("distribution/launcher/.enso.portable"),
        file("distribution/launcher/README.md")
      ),
      distributionRoot,
      cacheFactory.make("launcher-rootfiles")
    )
  }

  sealed trait OS {
    def name:                String
    def hasSupportForSulong: Boolean
    def executableName(base: String): String = base
    def archiveExt: String                   = ".tar.gz"
    def isUNIX: Boolean                      = true
    def archs: Seq[Architecture]
  }
  object OS {
    case object Linux extends OS {
      override val name: String                 = "linux"
      override val hasSupportForSulong: Boolean = true
      override val archs                        = Seq(Architecture.X64)
    }
    trait MacOS extends OS {
      override val name: String = "macos"
    }
    case object MacOSAmd extends MacOS {
      override val hasSupportForSulong: Boolean = true
      override val archs                        = Seq(Architecture.X64)
    }

    case object MacOSArm extends MacOS {
      override val hasSupportForSulong: Boolean = true
      override val archs                        = Seq(Architecture.AarchX64)
    }
    case object Windows extends OS {
      override val name: String                         = "windows"
      override val hasSupportForSulong: Boolean         = false
      override def executableName(base: String): String = base + ".exe"
      override def archiveExt: String                   = ".zip"
      override def isUNIX: Boolean                      = false
      override val archs                                = Seq(Architecture.X64)
    }

    val platforms = Seq(Linux, MacOSArm, MacOSAmd, Windows)

    def apply(name: String, arch: Option[String]): Option[OS] =
      name.toLowerCase match {
        case Linux.`name` => Some(Linux)
        case MacOSAmd.`name` =>
          arch match {
            case Some(Architecture.X64.`name`) =>
              Some(MacOSAmd)
            case Some(Architecture.AarchX64.`name`) =>
              Some(MacOSArm)
            case _ =>
              None
          }
        case MacOSArm.`name` => Some(MacOSArm)
        case Windows.`name`  => Some(Windows)
        case _               => None
      }
  }

  sealed trait Architecture {
    def name: String

    /** Name of the architecture for GraalVM releases
      */
    def graalName: String
  }
  object Architecture {
    case object X64 extends Architecture {
      override val name: String      = "amd64"
      override def graalName: String = "x64"
    }

    case object AarchX64 extends Architecture {
      override val name: String      = "aarch64"
      override def graalName: String = "x64"
    }

  }

  /** A helper class that manages building distribution artifacts. */
  class Builder(
    ensoVersion: String,
    graalVersion: String,
    graalJavaVersion: String,
    artifactRoot: File
  ) {

    def artifactName(
      component: String,
      os: OS,
      architecture: Architecture
    ): String =
      s"enso-$component-$ensoVersion-${os.name}-${architecture.name}"

    def graalInPackageName: String =
      s"graalvm-ce-java$graalJavaVersion-$graalVersion"

    private def extractZip(archive: File, root: File): Unit = {
      IO.createDirectory(root)
      val exitCode = Process(
        Seq("unzip", "-q", archive.toPath.toAbsolutePath.normalize.toString),
        cwd = Some(root)
      ).!
      if (exitCode != 0) {
        throw new RuntimeException(s"Cannot extract $archive.")
      }
    }

    private def listZip(archive: File): Seq[File] = {
      val suppressStdErr = ProcessLogger(_ => ())
      val zipList = Process(
        Seq("zip", "-l", archive.toPath.toAbsolutePath.normalize.toString)
      )
      zipList.lineStream(suppressStdErr).map(file)
    }

    private def extractTarGz(archive: File, root: File): Unit = {
      IO.createDirectory(root)
      val exitCode = Process(
        Seq(
          "tar",
          "xf",
          archive.toPath.toAbsolutePath.toString
        ),
        cwd = Some(root)
      ).!
      if (exitCode != 0) {
        throw new RuntimeException(s"Cannot extract $archive.")
      }
    }

    private def listTarGz(archive: File): Seq[File] = {
      val suppressStdErr = ProcessLogger(_ => ())
      val tarList =
        Process(Seq("tar", "tf", archive.toPath.toAbsolutePath.toString))
      tarList.lineStream(suppressStdErr).map(file)
    }

    private def extract(archive: File, root: File): Unit = {
      if (archive.getName.endsWith("zip")) {
        extractZip(archive, root)
      } else {
        extractTarGz(archive, root)
      }
    }

    private def list(archive: File): Seq[File] = {
      if (archive.getName.endsWith("zip")) {
        listZip(archive)
      } else {
        listTarGz(archive)
      }
    }

    private def graalArchive(os: OS, architecture: Architecture): File = {
      val packageDir =
        artifactRoot / s"graalvm-$graalVersion-${os.name}-${architecture.name}"
      if (!packageDir.exists()) {
        IO.createDirectory(packageDir)
      }
      val archiveName =
        s"graalvm-${os.name}-${architecture.name}-$graalVersion-$graalJavaVersion"
      packageDir / (archiveName + os.archiveExt)
    }

    private def downloadGraal(
      log: ManagedLogger,
      os: OS,
      architecture: Architecture
    ): File = {
      val archive = graalArchive(os, architecture)
      if (!archive.exists()) {
        log.info(
          s"Downloading GraalVM $graalVersion Java $graalJavaVersion " +
          s"for $os $architecture"
        )
        val graalUrl =
          s"https://github.com/graalvm/graalvm-ce-builds/releases/download/" +
          s"jdk-$graalJavaVersion/" +
          s"graalvm-community-jdk-${graalJavaVersion}_${os.name}-" +
          s"${architecture.graalName}_bin${os.archiveExt}"
        val exitCode = (url(graalUrl) #> archive).!
        if (exitCode != 0) {
          throw new RuntimeException(s"Graal download from $graalUrl failed.")
        }
      }

      archive
    }

    private def copyGraal(
      os: OS,
      architecture: Architecture,
      runtimeDir: File
    ): Unit = {
      val archive = graalArchive(os, architecture)
      extract(archive, runtimeDir)
    }

    /** Prepare the GraalVM package.
      *
      * @param log the logger
      * @param os the system type
      * @param architecture the architecture type
      * @return the path to the created GraalVM package
      */
    def createGraalPackage(
      log: ManagedLogger,
      os: OS,
      architecture: Architecture
    ): File = {
      log.info("Building GraalVM distribution")
      val archive = downloadGraal(log, os, architecture)

      if (os.hasSupportForSulong) {
        log.info("Building GraalVM distribution2")
        val packageDir         = archive.getParentFile
        val archiveRootDir     = list(archive).head.getTopDirectory.getName
        val extractedGraalDir0 = packageDir / archiveRootDir
        val graalRuntimeDir =
          s"graalvm-ce-java${graalJavaVersion}-${graalVersion}"
        val extractedGraalDir = packageDir / graalRuntimeDir

        if (extractedGraalDir0.exists()) {
          IO.delete(extractedGraalDir0)
        }
        if (extractedGraalDir.exists()) {
          IO.delete(extractedGraalDir)
        }

        log.info(s"Extracting $archive to $packageDir")
        extract(archive, packageDir)

        if (extractedGraalDir0 != extractedGraalDir) {
          log.info(s"Standardizing GraalVM directory name")
          IO.move(extractedGraalDir0, extractedGraalDir)
        }

        log.info("Installing components")
        gu(log, os, extractedGraalDir, "install", "python")

        log.info(s"Re-creating $archive")
        IO.delete(archive)
        makeArchive(packageDir, graalRuntimeDir, archive)

        log.info(s"Cleaning up $extractedGraalDir")
        IO.delete(extractedGraalDir)
      }
      archive
    }

    /** Run the `gu` executable from the GraalVM distribution.
      *
      * @param log the logger
      * @param os the system type
      * @param graalDir the directory with a GraalVM distribution
      * @param arguments the command arguments
      * @return Stdout from the `gu` command.
      */
    def gu(
      log: ManagedLogger,
      os: OS,
      graalDir: File,
      arguments: String*
    ): String = {
      val shallowFile = graalDir / "bin" / "gu"
      val deepFile    = graalDir / "Contents" / "Home" / "bin" / "gu"
      val executableFile = os match {
        case OS.Linux =>
          shallowFile
        case _: OS.MacOS =>
          if (deepFile.exists) {
            deepFile
          } else {
            shallowFile
          }
        case OS.Windows =>
          graalDir / "bin" / "gu.cmd"
      }
      val javaHomeFile = executableFile.getParentFile.getParentFile
      val javaHome     = javaHomeFile.toPath.toAbsolutePath
      val command =
        executableFile.toPath.toAbsolutePath.toString +: arguments

      log.debug(
        s"Running $command in $graalDir with JAVA_HOME=${javaHome.toString}"
      )

      try {
        Process(
          command,
          Some(graalDir),
          ("JAVA_HOME", javaHome.toString),
          ("GRAALVM_HOME", javaHome.toString)
        ).!!
      } catch {
        case _: RuntimeException =>
          throw new RuntimeException(
            s"Failed to run '${command.mkString(" ")}'"
          )
      }
    }

    def copyEngine(os: OS, architecture: Architecture, distDir: File): Unit = {
      val engine = builtArtifact("engine", os, architecture)
      if (!engine.exists()) {
        throw new IllegalStateException(
          s"Cannot create bundle for $os / $architecture because corresponding " +
          s"engine has not been built."
        )
      }

      IO.copyDirectory(engine / s"enso-$ensoVersion", distDir / ensoVersion)
    }

    def makeExecutable(file: File): Unit = {
      val ownerOnly = false
      file.setExecutable(true, ownerOnly)
    }

    def fixLauncher(root: File, os: OS): Unit = {
      makeExecutable(root / "enso" / "bin" / os.executableName("enso"))
      IO.createDirectories(
        Seq("dist", "config", "runtime").map(root / "enso" / _)
      )
    }

    def makeArchive(root: File, rootDir: String, target: File): Unit = {
      val exitCode = if (target.getName.endsWith("zip")) {
        Process(
          Seq(
            "zip",
            "-9",
            "-q",
            "-r",
            target.toPath.toAbsolutePath.normalize.toString,
            rootDir
          ),
          cwd = Some(root)
        ).!
      } else {
        Process(
          Seq(
            "tar",
            "--use-compress-program=gzip -9",
            "-cf",
            target.toPath.toAbsolutePath.normalize.toString,
            rootDir
          ),
          cwd = Some(root)
        ).!
      }
      if (exitCode != 0) {
        throw new RuntimeException(s"Failed to create archive $target")
      }
    }

    /** Path to an arbitrary built artifact. */
    def builtArtifact(
      component: String,
      os: OS,
      architecture: Architecture
    ): File = artifactRoot / artifactName(component, os, architecture)

    /** Path to the artifact that is built on this local machine. */
    def localArtifact(component: String): File = {
      val os =
        if (Platform.isWindows) OS.Windows
        else if (Platform.isLinux) OS.Linux
        else if (Platform.isMacOS) {
          if (Platform.isAmd64) OS.MacOSAmd
          else if (Platform.isArm64) OS.MacOSArm
          else
            throw new IllegalStateException(
              "Unknown Arch: " + sys.props("os.arch")
            )
        } else
          throw new IllegalStateException("Unknown OS: " + sys.props("os.name"))
      artifactRoot / artifactName(component, os, os.archs.head)
    }

    /** Path to a built archive.
      *
      * These archives are built by [[makePackages]] and [[makeBundles]].
      */
    def builtArchive(
      component: String,
      os: OS,
      architecture: Architecture
    ): File =
      artifactRoot / (artifactName(
        component,
        os,
        architecture
      ) + os.archiveExt)

    private def cleanDirectory(dir: File): Unit = {
      for (f <- IO.listFiles(dir)) {
        IO.delete(f)
      }
    }

    /** Creates compressed and ready for release packages for the launcher and
      * engine.
      *
      * A project manager package is not created, as we release only its bundle.
      * See [[makeBundles]].
      *
      * It does not trigger any builds. Instead, it uses available artifacts
      * placed in `artifactRoot`. These artifacts may be created using the
      * `enso/build*Distribution` tasks or they may come from other workers (as
      * is the case in the release CI where the artifacts are downloaded from
      * other jobs).
      */
    def makePackages = Command.command("makePackages") { state =>
      val log = state.log
      for {
        os   <- OS.platforms
        arch <- os.archs
      } {
        val launcher = builtArtifact("launcher", os, arch)
        if (launcher.exists()) {
          fixLauncher(launcher, os)
          val archive = builtArchive("launcher", os, arch)
          makeArchive(launcher, "enso", archive)
          log.info(s"Created $archive")
        }

        val engine = builtArtifact("engine", os, arch)
        if (engine.exists()) {
          if (os.isUNIX) {
            makeExecutable(engine / s"enso-$ensoVersion" / "bin" / "enso")
          }
          val archive = builtArchive("engine", os, arch)
          makeArchive(engine, s"enso-$ensoVersion", archive)
          log.info(s"Created $archive")
        }
      }
      state
    }

    /** Creates launcher and project-manager bundles that include the component
      * itself, the engine and a Graal runtime.
      *
      * It will download the GraalVM runtime and cache it in `artifactRoot` so
      * further invocations for the same version will not need to download it.
      *
      * It does not trigger any builds. Instead, it uses available artifacts
      * placed in `artifactRoot`. These artifacts may be created using the
      * `enso/build*Distribution` tasks or they may come from other workers (as
      * is the case in the release CI where the artifacts are downloaded from
      * other jobs).
      */
    def makeBundles = Command.command("makeBundles") { state =>
      val log = state.log
      for {
        os   <- OS.platforms
        arch <- os.archs
      } {
        val launcher = builtArtifact("launcher", os, arch)
        if (launcher.exists()) {
          fixLauncher(launcher, os)
          copyEngine(os, arch, launcher / "enso" / "dist")
          copyGraal(
            os,
            arch,
            launcher / "enso" / "runtime" / s"graalvm-ce-java$graalJavaVersion-$graalVersion/"
          )

          val archive = builtArchive("bundle", os, arch)
          makeArchive(launcher, "enso", archive)

          cleanDirectory(launcher / "enso" / "dist")
          cleanDirectory(launcher / "enso" / "runtime")

          log.info(s"Created $archive")
        }

        val pm = builtArtifact("project-manager", os, arch)
        if (pm.exists()) {
          if (os.isUNIX) {
            makeExecutable(pm / "enso" / "bin" / "project-manager")
          }

          copyEngine(os, arch, pm / "enso" / "dist")
          copyGraal(
            os,
            arch,
            pm / "enso" / "runtime" / s"graalvm-ce-java$graalJavaVersion-$graalVersion/"
          )

          IO.copyFile(
            file("distribution/enso.bundle.template"),
            pm / "enso" / ".enso.bundle"
          )

          val archive = builtArchive("project-manager", os, arch)
          makeArchive(pm, "enso", archive)

          cleanDirectory(pm / "enso" / "dist")
          cleanDirectory(pm / "enso" / "runtime")

          log.info(s"Created $archive")
        }
      }
      state
    }
  }
}
