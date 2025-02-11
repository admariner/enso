package org.enso.launcher.distribution

import org.enso.distribution.locking.ThreadSafeFileLockManager
import org.enso.distribution.{
  PortableDistributionManager,
  TemporaryDirectoryManager
}
import org.enso.launcher.cli.{
  CLIRuntimeVersionManagementUserInterface,
  GlobalCLIOptions
}
import org.enso.runtimeversionmanager.components._
import org.enso.runtimeversionmanager.releases.engine.EngineRepository
import org.enso.runtimeversionmanager.releases.graalvm.GraalCEReleaseProvider

/** Gathers default managers related to distribution used in the launcher. */
object DefaultManagers {

  /** Default distribution manager that is capable of detecting portable mode. */
  val distributionManager = new PortableDistributionManager(LauncherEnvironment)

  /** Default [[LockManager]] storing lock files in a directory defined by
    * the distribution manager.
    *
    * It is lazily initialized, because initializing it triggers path detection
    * and this cannot happen within static initialization, because with Native
    * Image, static initialization is done at build-time, so the paths would be
    * set at build time and not actual runtime, leading to very wrong results.
    */
  lazy val defaultFileLockManager =
    new ThreadSafeFileLockManager(distributionManager.paths.locks)

  /** Default [[LauncherResourceManager]] using the [[defaultFileLockManager]]. */
  lazy val defaultResourceManager = new LauncherResourceManager(
    defaultFileLockManager
  )

  /** Default [[TemporaryDirectoryManager]]. */
  lazy val temporaryDirectoryManager =
    TemporaryDirectoryManager(distributionManager, defaultResourceManager)

  /** Creates a [[RuntimeVersionManager]] that uses the default distribution. */
  def runtimeVersionManager(
    globalCLIOptions: GlobalCLIOptions,
    alwaysInstallMissing: Boolean
  ): RuntimeVersionManager =
    new RuntimeVersionManager(
      LauncherEnvironment,
      new CLIRuntimeVersionManagementUserInterface(
        globalCLIOptions,
        alwaysInstallMissing
      ),
      distributionManager,
      new GraalVersionManager(distributionManager, LauncherEnvironment),
      temporaryDirectoryManager,
      defaultResourceManager,
      EngineRepository.defaultEngineReleaseProvider,
      GraalCEReleaseProvider.default,
      InstallerKind.Launcher
    )
}
