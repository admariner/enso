package org.enso.runner;

import static scala.jdk.javaapi.CollectionConverters.asJava;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.TreeSet;
import org.enso.compiler.core.EnsoParser;
import org.enso.compiler.core.ir.module.scope.imports.Polyglot;
import org.enso.filesystem.FileSystem$;
import org.enso.pkg.NativeLibraryFinder;
import org.enso.pkg.PackageManager$;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeProxyCreation;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

public final class EnsoLibraryFeature implements Feature {
  private static final String LIB_OUTPUT = "org.enso.feature.native.lib.output";
  private final File nativeLibDir;

  public EnsoLibraryFeature() {
    var nativeLibOut = System.getProperty(LIB_OUTPUT);
    if (nativeLibOut == null) {
      throw new IllegalStateException("Missing system property: " + LIB_OUTPUT);
    }
    nativeLibDir = new File(nativeLibOut);
    if (!nativeLibDir.exists() || !nativeLibDir.isDirectory()) {
      var created = nativeLibDir.mkdirs();
      if (!created) {
        throw new IllegalStateException("Cannot create directory: " + nativeLibDir);
      }
    }
  }

  @Override
  public void beforeAnalysis(BeforeAnalysisAccess access) {

    var libs = new LinkedHashSet<Path>();
    for (var p : access.getApplicationClassPath()) {
      var p1 = p.getParent();
      if (p1 != null && p1.getFileName().toString().equals("java")) {
        var p2 = p1.getParent();
        if (p2 != null
            && p2.getFileName().toString().equals("polyglot")
            && p2.getParent() != null) {
          libs.add(p2.getParent());
        }
      }
    }

    /*
      To run Standard.Test one shall analyze its polyglot/java files. But there are none
      to include on classpath as necessary test classes are included in Standard.Base!
      We can locate the Test library by following code or we can make sure all necessary
      imports are already mentioned in Standard.Base itself.

    if (!libs.isEmpty()) {
      var f = libs.iterator().next();
      var stdTest = f.getParent().getParent().resolve("Test").resolve(f.getFileName());
      if (stdTest.toFile().exists()) {
        libs.add(stdTest);
      }
      System.err.println("Testing library: " + stdTest);
    }
    */

    var classes = new TreeSet<String>();
    var nativeLibPaths = new TreeSet<String>();
    try {
      for (var p : libs) {
        var result = PackageManager$.MODULE$.Default().loadPackage(p.toFile());
        if (result.isSuccess()) {
          var pkg = result.get();
          for (var src : pkg.listSourcesJava()) {
            var code = Files.readString(src.file().toPath());
            var ir = EnsoParser.compile(code);
            for (var imp : asJava(ir.imports())) {
              if (imp instanceof Polyglot poly && poly.entity() instanceof Polyglot.Java entity) {
                var name = new StringBuilder(entity.getJavaName());
                Class<?> clazz;
                for (; ; ) {
                  clazz = access.findClassByName(name.toString());
                  if (clazz != null) {
                    break;
                  }
                  int at = name.toString().lastIndexOf('.');
                  if (at < 0) {
                    throw new IllegalStateException("Cannot load " + entity.getJavaName());
                  }
                  name.setCharAt(at, '$');
                }
                classes.add(clazz.getName());
                RuntimeReflection.register(clazz);
                RuntimeReflection.register(clazz.getConstructors());
                RuntimeReflection.register(clazz.getMethods());
                RuntimeReflection.register(clazz.getFields());
                RuntimeReflection.registerAllConstructors(clazz);
                RuntimeReflection.registerAllFields(clazz);
                RuntimeReflection.registerAllMethods(clazz);
                if (clazz.isInterface()) {
                  RuntimeProxyCreation.register(clazz);
                }
              }
            }
          }
          if (pkg.nativeLibraryDir().exists()) {
            var nativeLibs =
                NativeLibraryFinder.listAllNativeLibraries(pkg, FileSystem$.MODULE$.defaultFs());
            for (var nativeLib : nativeLibs) {
              var out = new File(nativeLibDir, nativeLib.getName());
              Files.copy(nativeLib.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
              nativeLibPaths.add(out.getAbsolutePath());
            }
          }
        }
      }
    } catch (Exception ex) {
      ex.printStackTrace(System.err);
      throw new IllegalStateException(ex);
    }
    System.err.println("Summary for polyglot import java:");
    for (var className : classes) {
      System.err.println("  " + className);
    }
    System.err.println("Registered " + classes.size() + " classes for reflection");
    System.err.println("Copied native libraries: " + nativeLibPaths + " into " + nativeLibDir);
  }
}
