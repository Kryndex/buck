/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.apple;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListFormatException;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cxx.BsdArchiver;
import com.facebook.buck.cxx.CompilerProvider;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.cxx.CxxToolProvider;
import com.facebook.buck.cxx.DebugPathSanitizer;
import com.facebook.buck.cxx.DefaultLinkerProvider;
import com.facebook.buck.cxx.HeaderVerification;
import com.facebook.buck.cxx.LinkerProvider;
import com.facebook.buck.cxx.Linkers;
import com.facebook.buck.cxx.MungingDebugPathSanitizer;
import com.facebook.buck.cxx.PosixNmSymbolNameTool;
import com.facebook.buck.cxx.PrefixMapDebugPathSanitizer;
import com.facebook.buck.cxx.PreprocessorProvider;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.UserFlavor;
import com.facebook.buck.rules.ConstantToolProvider;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.VersionedTool;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.swift.SwiftPlatform;
import com.facebook.buck.swift.SwiftPlatforms;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;

/**
 * Utility class to create Objective-C/C/C++/Objective-C++ platforms to
 * support building iOS and Mac OS X products with Xcode.
 */
public class AppleCxxPlatforms {

  private static final Logger LOG = Logger.get(AppleCxxPlatforms.class);

  // Utility class, do not instantiate.
  private AppleCxxPlatforms() { }

  private static final String USR_BIN = "usr/bin";

  public static ImmutableList<AppleCxxPlatform> buildAppleCxxPlatforms(
      ProjectFilesystem filesystem,
      BuckConfig buckConfig,
      SwiftBuckConfig swiftBuckConfig,
      ProcessExecutor processExecutor)
      throws IOException {
    AppleConfig appleConfig = buckConfig.getView(AppleConfig.class);
    Supplier<Optional<Path>> appleDeveloperDirectorySupplier =
        appleConfig.getAppleDeveloperDirectorySupplier(processExecutor);
    Optional<Path> appleDeveloperDirectory = appleDeveloperDirectorySupplier.get();
    if (appleDeveloperDirectory.isPresent() &&
        !Files.isDirectory(appleDeveloperDirectory.get())) {
      LOG.error(
          "Developer directory is set to %s, but is not a directory",
          appleDeveloperDirectory.get());
      return ImmutableList.of();
    }

    ImmutableList.Builder<AppleCxxPlatform> appleCxxPlatformsBuilder = ImmutableList.builder();
    ImmutableMap<String, AppleToolchain> toolchains =
        AppleToolchainDiscovery.discoverAppleToolchains(
            appleDeveloperDirectory,
            appleConfig.getExtraToolchainPaths());

    ImmutableMap<AppleSdk, AppleSdkPaths> sdkPaths = AppleSdkDiscovery.discoverAppleSdkPaths(
        appleDeveloperDirectory,
        appleConfig.getExtraPlatformPaths(),
        toolchains,
        appleConfig);

    Optional<String> swiftVersion = swiftBuckConfig.getVersion();
    Optional<AppleToolchain> swiftToolChain;
    if (swiftVersion.isPresent()) {
      Optional<String> swiftToolChainName =
          swiftVersion.map(AppleCxxPlatform.SWIFT_VERSION_TO_TOOLCHAIN_IDENTIFIER);
      swiftToolChain = toolchains.values().stream()
          .filter(input -> input.getIdentifier().equals(swiftToolChainName.get()))
          .findFirst();
    } else {
      swiftToolChain = Optional.empty();
    }

    XcodeToolFinder xcodeToolFinder = new XcodeToolFinder();
    XcodeBuildVersionCache xcodeBuildVersionCache = new XcodeBuildVersionCache();
    sdkPaths.forEach((sdk, appleSdkPaths) -> {
      String targetSdkVersion =
          appleConfig.getTargetSdkVersion(sdk.getApplePlatform())
              .orElse(sdk.getVersion());
      LOG.debug("SDK %s using default version %s", sdk, targetSdkVersion);
      for (String architecture : sdk.getArchitectures()) {
        appleCxxPlatformsBuilder.add(
            buildWithExecutableChecker(
                filesystem,
                sdk,
                targetSdkVersion,
                architecture,
                appleSdkPaths,
                buckConfig,
                xcodeToolFinder,
                xcodeBuildVersionCache,
                swiftToolChain));
      }
    });
    return appleCxxPlatformsBuilder.build();
  }

  @VisibleForTesting
  static AppleCxxPlatform buildWithExecutableChecker(
      ProjectFilesystem filesystem,
      AppleSdk targetSdk,
      String minVersion,
      String targetArchitecture,
      final AppleSdkPaths sdkPaths,
      BuckConfig buckConfig,
      XcodeToolFinder xcodeToolFinder,
      XcodeBuildVersionCache xcodeBuildVersionCache,
      Optional<AppleToolchain> swiftToolChain) {
    AppleCxxPlatform.Builder platformBuilder = AppleCxxPlatform.builder();

    ImmutableList.Builder<Path> toolSearchPathsBuilder = ImmutableList.builder();
    // Search for tools from most specific to least specific.
    toolSearchPathsBuilder
        .add(sdkPaths.getSdkPath().resolve(USR_BIN))
        .add(sdkPaths.getSdkPath().resolve("Developer").resolve(USR_BIN))
        .add(sdkPaths.getPlatformPath().resolve("Developer").resolve(USR_BIN));
    for (Path toolchainPath : sdkPaths.getToolchainPaths()) {
      toolSearchPathsBuilder.add(toolchainPath.resolve(USR_BIN));
    }
    if (sdkPaths.getDeveloperPath().isPresent()) {
      toolSearchPathsBuilder.add(sdkPaths.getDeveloperPath().get().resolve(USR_BIN));
      toolSearchPathsBuilder.add(sdkPaths.getDeveloperPath().get().resolve("Tools"));
    }

    // TODO(beng): Add more and better cflags.
    ImmutableList.Builder<String> cflagsBuilder = ImmutableList.builder();
    cflagsBuilder.add("-isysroot", sdkPaths.getSdkPath().toString());
    cflagsBuilder.add("-iquote", filesystem.getRootPath().toString());
    cflagsBuilder.add("-arch", targetArchitecture);
    cflagsBuilder.add(targetSdk.getApplePlatform().getMinVersionFlagPrefix() + minVersion);

    if (targetSdk.getApplePlatform().equals(ApplePlatform.WATCHOS)) {
      cflagsBuilder.add("-fembed-bitcode");
    }

    ImmutableList.Builder<String> ldflagsBuilder = ImmutableList.builder();
    ldflagsBuilder.addAll(Linkers.iXlinker("-sdk_version", targetSdk.getVersion(), "-ObjC"));
    if (targetSdk.getApplePlatform().equals(ApplePlatform.WATCHOS)) {
      ldflagsBuilder.addAll(
          Linkers.iXlinker("-bitcode_verify", "-bitcode_hide_symbols", "-bitcode_symbol_map"));
    }


    // Populate Xcode version keys from Xcode's own Info.plist if available.
    Optional<String> xcodeBuildVersion = Optional.empty();
    Optional<Path> developerPath = sdkPaths.getDeveloperPath();
    if (developerPath.isPresent()) {
      Path xcodeBundlePath = developerPath.get().getParent();
      if (xcodeBundlePath != null) {
        Path xcodeInfoPlistPath = xcodeBundlePath.resolve("Info.plist");
        try (InputStream stream = Files.newInputStream(xcodeInfoPlistPath)) {
          NSDictionary parsedXcodeInfoPlist = (NSDictionary) PropertyListParser.parse(stream);

          NSObject xcodeVersionObject = parsedXcodeInfoPlist.objectForKey("DTXcode");
          if (xcodeVersionObject != null) {
            Optional<String> xcodeVersion = Optional.of(xcodeVersionObject.toString());
            platformBuilder.setXcodeVersion(xcodeVersion);
          }
        } catch (IOException e) {
          LOG.warn(
              "Error reading Xcode's info plist %s; ignoring Xcode versions",
              xcodeInfoPlistPath);
        } catch (
            PropertyListFormatException |
                ParseException |
                ParserConfigurationException |
                SAXException e) {
          LOG.warn("Error in parsing %s; ignoring Xcode versions", xcodeInfoPlistPath);
        }
      }

      xcodeBuildVersion = xcodeBuildVersionCache.lookup(developerPath.get());
      LOG.debug("Xcode build version is: " + xcodeBuildVersion.orElse("<absent>"));
    }

    ImmutableList.Builder<String> versions = ImmutableList.builder();
    versions.add(targetSdk.getVersion());

    ImmutableList<String> toolchainVersions =
        targetSdk.getToolchains().stream()
            .map(AppleToolchain::getVersion)
            .flatMap(Optionals::toStream)
            .collect(MoreCollectors.toImmutableList());
    if (toolchainVersions.isEmpty()) {
      if (!xcodeBuildVersion.isPresent()) {
        throw new HumanReadableException("Failed to read toolchain versions and Xcode version.");
      }
      versions.add(xcodeBuildVersion.get());
    } else {
      versions.addAll(toolchainVersions);
    }

    String version = Joiner.on(':').join(versions.build());

    ImmutableList<Path> toolSearchPaths = toolSearchPathsBuilder.build();

    Tool clangPath = VersionedTool.of(
        getToolPath("clang", toolSearchPaths, xcodeToolFinder),
        "apple-clang",
        version);

    Tool clangXxPath = VersionedTool.of(
        getToolPath("clang++", toolSearchPaths, xcodeToolFinder),
        "apple-clang++",
        version);

    Tool ar = VersionedTool.of(
        getToolPath("ar", toolSearchPaths, xcodeToolFinder),
        "apple-ar",
        version);

    Tool ranlib = VersionedTool.builder()
        .setPath(getToolPath("ranlib", toolSearchPaths, xcodeToolFinder))
        .setName("apple-ranlib")
        .setVersion(version)
        .build();

    Tool strip = VersionedTool.of(
        getToolPath("strip", toolSearchPaths, xcodeToolFinder),
        "apple-strip",
        version);

    Tool nm = VersionedTool.of(
        getToolPath("nm", toolSearchPaths, xcodeToolFinder),
        "apple-nm",
        version);

    Tool actool = VersionedTool.of(
        getToolPath("actool", toolSearchPaths, xcodeToolFinder),
        "apple-actool",
        version);

    Tool ibtool = VersionedTool.of(
        getToolPath("ibtool", toolSearchPaths, xcodeToolFinder),
        "apple-ibtool",
        version);

    Tool momc = VersionedTool.of(
        getToolPath("momc", toolSearchPaths, xcodeToolFinder),
        "apple-momc",
        version);

    Tool xctest = VersionedTool.of(
        getToolPath("xctest", toolSearchPaths, xcodeToolFinder),
        "apple-xctest",
        version);

    Tool dsymutil = VersionedTool.of(
        getToolPath("dsymutil", toolSearchPaths, xcodeToolFinder),
        "apple-dsymutil",
        version);

    Tool lipo = VersionedTool.of(
        getToolPath("lipo", toolSearchPaths, xcodeToolFinder),
        "apple-lipo",
        version);

    Tool lldb = VersionedTool.of(
        getToolPath("lldb", toolSearchPaths, xcodeToolFinder),
        "lldb",
        version);

    Optional<Path> stubBinaryPath = targetSdk.getApplePlatform().getStubBinaryPath()
        .map(input -> sdkPaths.getSdkPath().resolve(input));

    CxxBuckConfig config = new CxxBuckConfig(buckConfig);

    UserFlavor targetFlavor = UserFlavor.of(
        Flavor.replaceInvalidCharacters(
            targetSdk.getName() + "-" + targetArchitecture),
        String.format("SDK: %s, architecture: %s", targetSdk.getName(), targetArchitecture));

    ImmutableBiMap.Builder<Path, Path> sanitizerPaths = ImmutableBiMap.builder();
    sanitizerPaths.put(sdkPaths.getSdkPath(), Paths.get("APPLE_SDKROOT"));
    sanitizerPaths.put(sdkPaths.getPlatformPath(), Paths.get("APPLE_PLATFORM_DIR"));
    if (sdkPaths.getDeveloperPath().isPresent()) {
      sanitizerPaths.put(sdkPaths.getDeveloperPath().get(), Paths.get("APPLE_DEVELOPER_DIR"));
    }

    DebugPathSanitizer compilerDebugPathSanitizer = new PrefixMapDebugPathSanitizer(
        config.getDebugPathSanitizerLimit(),
        File.separatorChar,
        Paths.get("."),
        sanitizerPaths.build(),
        filesystem.getRootPath().toAbsolutePath(),
        CxxToolProvider.Type.CLANG,
        filesystem);
    DebugPathSanitizer assemblerDebugPathSanitizer = new MungingDebugPathSanitizer(
        config.getDebugPathSanitizerLimit(),
        File.separatorChar,
        Paths.get("."),
        sanitizerPaths.build());

    ImmutableList<String> cflags = cflagsBuilder.build();

    ImmutableMap.Builder<String, String> macrosBuilder = ImmutableMap.builder();
    macrosBuilder.put("SDKROOT", sdkPaths.getSdkPath().toString());
    macrosBuilder.put("PLATFORM_DIR", sdkPaths.getPlatformPath().toString());
    macrosBuilder.put("CURRENT_ARCH", targetArchitecture);
    if (sdkPaths.getDeveloperPath().isPresent()) {
      macrosBuilder.put("DEVELOPER_DIR", sdkPaths.getDeveloperPath().get().toString());
    }
    ImmutableMap<String, String> macros = macrosBuilder.build();

    Optional<String> buildVersion = Optional.empty();
    Path platformVersionPlistPath = sdkPaths.getPlatformPath().resolve("version.plist");
    try (InputStream versionPlist = Files.newInputStream(platformVersionPlistPath)) {
      NSDictionary versionInfo = (NSDictionary) PropertyListParser.parse(versionPlist);
      if (versionInfo != null) {
        NSObject productBuildVersion = versionInfo.objectForKey("ProductBuildVersion");
        if (productBuildVersion != null) {
          buildVersion = Optional.of(productBuildVersion.toString());
        } else {
          LOG.warn(
              "In %s, missing ProductBuildVersion. Build version will be unset for this platform.",
              platformVersionPlistPath);
        }
      } else {
        LOG.warn(
            "Empty version plist in %s. Build version will be unset for this platform.",
            platformVersionPlistPath);
      }
    } catch (NoSuchFileException e) {
      LOG.warn(
          "%s does not exist. Build version will be unset for this platform.",
          platformVersionPlistPath);
    } catch (PropertyListFormatException | SAXException | ParserConfigurationException |
        ParseException | IOException e) {
      // Some other error occurred, print the exception since it may contain error details.
      LOG.warn(
          e,
          "Failed to parse %s. Build version will be unset for this platform.",
          platformVersionPlistPath);
    }

    PreprocessorProvider aspp =
        new PreprocessorProvider(
            new ConstantToolProvider(clangPath),
            CxxToolProvider.Type.CLANG);
    CompilerProvider as =
        new CompilerProvider(
            new ConstantToolProvider(clangPath),
            CxxToolProvider.Type.CLANG);
    PreprocessorProvider cpp =
        new PreprocessorProvider(
            new ConstantToolProvider(clangPath),
            CxxToolProvider.Type.CLANG);
    CompilerProvider cc =
        new CompilerProvider(
            new ConstantToolProvider(clangPath),
            CxxToolProvider.Type.CLANG);
    PreprocessorProvider cxxpp =
        new PreprocessorProvider(
            new ConstantToolProvider(clangXxPath),
            CxxToolProvider.Type.CLANG);
    CompilerProvider cxx =
        new CompilerProvider(
            new ConstantToolProvider(clangXxPath),
            CxxToolProvider.Type.CLANG);
    ImmutableList.Builder<String> whitelistBuilder = ImmutableList.builder();
    whitelistBuilder.add("^" + Pattern.quote(sdkPaths.getSdkPath().toString()) + "\\/.*");
    whitelistBuilder.add("^" + Pattern.quote(sdkPaths.getPlatformPath().toString() +
                         "/Developer/Library/Frameworks") + "\\/.*");
    for (Path toolchainPath : sdkPaths.getToolchainPaths()) {
      LOG.debug("Apple toolchain path: %s", toolchainPath);
      try {
        whitelistBuilder.add("^" + Pattern.quote(toolchainPath.toRealPath().toString()) + "\\/.*");
      } catch (IOException e) {
        LOG.warn(e, "Apple toolchain path could not be resolved: %s", toolchainPath);
      }
    }
    HeaderVerification headerVerification =
        config.getHeaderVerification().withPlatformWhitelist(whitelistBuilder.build());
    LOG.debug("Headers verification platform whitelist: %s",
        headerVerification.getPlatformWhitelist());

    CxxPlatform cxxPlatform = CxxPlatforms.build(
        targetFlavor,
        Platform.MACOS,
        config,
        as,
        aspp,
        cc,
        cxx,
        cpp,
        cxxpp,
        new DefaultLinkerProvider(
            LinkerProvider.Type.DARWIN,
            new ConstantToolProvider(clangXxPath)),
        ImmutableList.<String>builder()
            .addAll(cflags)
            .addAll(ldflagsBuilder.build())
            .build(),
        strip,
        new BsdArchiver(ar),
        ranlib,
        new PosixNmSymbolNameTool(nm),
        cflagsBuilder.build(),
        ImmutableList.of(),
        cflags,
        ImmutableList.of(),
        "dylib",
        "%s.dylib",
        "a",
        "o",
        compilerDebugPathSanitizer,
        assemblerDebugPathSanitizer,
        macros,
        Optional.empty(),
        headerVerification);

    ApplePlatform applePlatform = targetSdk.getApplePlatform();
    ImmutableList.Builder<Path> swiftOverrideSearchPathBuilder = ImmutableList.builder();
    AppleSdkPaths.Builder swiftSdkPathsBuilder = AppleSdkPaths.builder().from(sdkPaths);
    if (swiftToolChain.isPresent()) {
      swiftOverrideSearchPathBuilder.add(swiftToolChain.get().getPath().resolve(USR_BIN));
      swiftSdkPathsBuilder.setToolchainPaths(ImmutableList.of(swiftToolChain.get().getPath()));
    }
    Optional<SwiftPlatform> swiftPlatform = getSwiftPlatform(
        applePlatform.getName(),
        targetArchitecture + "-apple-" +
            applePlatform.getSwiftName().orElse(applePlatform.getName()) + minVersion,
        version,
        swiftSdkPathsBuilder.build(),
        swiftOverrideSearchPathBuilder
            .addAll(toolSearchPaths)
            .build(),
        xcodeToolFinder);

    AppleConfig appleConfig = buckConfig.getView(AppleConfig.class);
    platformBuilder
        .setCxxPlatform(cxxPlatform)
        .setSwiftPlatform(swiftPlatform)
        .setAppleSdk(targetSdk)
        .setAppleSdkPaths(sdkPaths)
        .setMinVersion(minVersion)
        .setBuildVersion(buildVersion)
        .setActool(actool)
        .setIbtool(ibtool)
        .setMomc(momc)
        .setCopySceneKitAssets(
            getOptionalTool("copySceneKitAssets", toolSearchPaths, xcodeToolFinder, version))
        .setXctest(xctest)
        .setDsymutil(dsymutil)
        .setLipo(lipo)
        .setStubBinary(stubBinaryPath)
        .setLldb(lldb)
        .setCodesignAllocate(
            getOptionalTool("codesign_allocate", toolSearchPaths, xcodeToolFinder, version))
        .setCodesignProvider(appleConfig.getCodesignProvider());

    return platformBuilder.build();
  }

  private static Optional<SwiftPlatform> getSwiftPlatform(
      String platformName,
      String targetArchitectureName,
      String version,
      AbstractAppleSdkPaths sdkPaths,
      ImmutableList<Path> toolSearchPaths,
      XcodeToolFinder xcodeToolFinder) {
    ImmutableList<String> swiftParams = ImmutableList.of(
        "-frontend",
        "-sdk",
        sdkPaths.getSdkPath().toString(),
        "-target",
        targetArchitectureName);

    ImmutableList.Builder<String> swiftStdlibToolParamsBuilder = ImmutableList.builder();
    swiftStdlibToolParamsBuilder
      .add("--copy")
      .add("--verbose")
      .add("--strip-bitcode")
      .add("--platform")
      .add(platformName);
    for (Path toolchainPath : sdkPaths.getToolchainPaths()) {
      swiftStdlibToolParamsBuilder
        .add("--toolchain")
        .add(toolchainPath.toString());
    }

    Optional<Tool> swiftc = getOptionalToolWithParams(
        "swiftc",
        toolSearchPaths,
        xcodeToolFinder,
        version,
        swiftParams);
    Optional<Tool> swiftStdLibTool = getOptionalToolWithParams(
        "swift-stdlib-tool",
        toolSearchPaths,
        xcodeToolFinder,
        version,
        swiftStdlibToolParamsBuilder.build());

    if (swiftc.isPresent() && swiftStdLibTool.isPresent()) {
      return Optional.of(
          SwiftPlatforms.build(
              platformName,
              sdkPaths.getToolchainPaths(),
              swiftc.get(),
              swiftStdLibTool.get()));
    } else {
      return Optional.empty();
    }
  }

  private static Optional<Tool> getOptionalTool(
      String tool,
      ImmutableList<Path> toolSearchPaths,
      XcodeToolFinder xcodeToolFinder,
      String version) {
    return getOptionalToolWithParams(
        tool,
        toolSearchPaths,
        xcodeToolFinder,
        version,
        ImmutableList.of());
  }

  private static Optional<Tool> getOptionalToolWithParams(
      final String tool,
      ImmutableList<Path> toolSearchPaths,
      XcodeToolFinder xcodeToolFinder,
      final String version,
      final ImmutableList<String> params) {
    return xcodeToolFinder.getToolPath(toolSearchPaths, tool).map(input ->
        VersionedTool.builder()
            .setPath(input)
            .setName(tool)
            .setVersion(version)
            .setExtraArgs(params)
            .build());
  }

  private static Path getToolPath(
      String tool,
      ImmutableList<Path> toolSearchPaths,
      XcodeToolFinder xcodeToolFinder) {
    Optional<Path> result = xcodeToolFinder.getToolPath(toolSearchPaths, tool);
    if (!result.isPresent()) {
      throw new HumanReadableException("Cannot find tool %s in paths %s", tool, toolSearchPaths);
    }
    return result.get();
  }

  @VisibleForTesting
  static class XcodeBuildVersionCache {
    private final Map<Path, Optional<String>> cache = new HashMap<>();

    /**
     * Returns the Xcode build version. This is an alphanumeric string as output by
     * {@code xcodebuild -version} and shown in the About Xcode window. This value is embedded into
     * the plist of app bundles built by Xcode, under the field named {@code DTXcodeBuild}
     *
     * @param developerDir Path to developer dir, i.e. /Applications/Xcode.app/Contents/Developer
     * @return the xcode build version if found, nothing if it fails to be found, or the version
     *         plist file cannot be read.
     */
    Optional<String> lookup(Path developerDir) {
      return cache.computeIfAbsent(developerDir, ignored -> {
        Path versionPlist = developerDir.getParent().resolve("version.plist");
        NSString result;
        try {
          NSDictionary dict =
              (NSDictionary) PropertyListParser.parse(Files.readAllBytes(versionPlist));
          result = (NSString) dict.get("ProductBuildVersion");
        } catch (IOException | ClassCastException | SAXException | PropertyListFormatException |
            ParseException e) {
          LOG.warn(
              e,
              "%s: Cannot find xcode build version, file is in an invalid format.",
              versionPlist);
          return Optional.empty();
        } catch (ParserConfigurationException e) {
          throw new IllegalStateException("plist parser threw unexpected exception", e);
        }
        if (result != null) {
          return Optional.of(result.toString());
        } else {
          LOG.warn(
              "%s: Cannot find xcode build version, file is in an invalid format.", versionPlist);
          return Optional.empty();
        }
      });
    }
  }
}
