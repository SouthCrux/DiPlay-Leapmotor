#!/usr/bin/env python3
"""Generate the small, dependency-free Xcode probe project and its property lists."""
from pathlib import Path
import hashlib
import json
import plistlib

ROOT = Path(__file__).resolve().parent
objects = {}


def ident(name):
    return hashlib.sha256(name.encode()).hexdigest()[:24].upper()


def obj(name, body):
    objects[ident(name)] = body
    return ident(name)


def quoted(value):
    return json.dumps(value)


def array(values):
    return "(" + ", ".join(values) + ")"


def plist(relative, data):
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(plistlib.dumps(data, sort_keys=False))


common_info = dict(CFBundleDevelopmentRegion="es", CFBundleExecutable="$(EXECUTABLE_NAME)",
                   CFBundleIdentifier="$(PRODUCT_BUNDLE_IDENTIFIER)", CFBundleInfoDictionaryVersion="6.0",
                   CFBundleName="$(PRODUCT_NAME)", CFBundleShortVersionString="0.1.0",
                   CFBundleVersion="1", ProbeAppGroup="$(SIRI_PROBE_APP_GROUP)")
plist("App/Info.plist", dict(common_info, CFBundlePackageType="APPL",
      CFBundleDisplayName="DiPlay Prueba", LSRequiresIPhoneOS=True,
      NSSiriUsageDescription="Probar consultas de batería y autonomía de un vehículo simulado.",
      UILaunchScreen={}, UIApplicationSceneManifest={"UIApplicationSupportsMultipleScenes": False},
      UISupportedInterfaceOrientations=["UIInterfaceOrientationPortrait", "UIInterfaceOrientationLandscapeLeft",
                                        "UIInterfaceOrientationLandscapeRight"]))
plist("Extension/Info.plist", dict(common_info, CFBundlePackageType="XPC!", CFBundleDisplayName="DiPlay Prueba Siri",
      NSExtension={"NSExtensionPointIdentifier": "com.apple.intents-service",
                   "NSExtensionPrincipalClass": "$(PRODUCT_MODULE_NAME).IntentHandler",
                   "NSExtensionAttributes": {"IntentsSupported": ["INGetCarPowerLevelStatusIntent"]}}))
for target in ("App", "Extension"):
    plist(f"{target}/{target}.entitlements", {"com.apple.developer.siri": True,
          "com.apple.security.application-groups": ["$(SIRI_PROBE_APP_GROUP)"]})

files = {}
for path in ("App/ProbeApp.swift", "Extension/IntentHandler.swift", "Shared/ProbeStore.swift", "Config.xcconfig",
             "App/Info.plist", "Extension/Info.plist", "App/App.entitlements", "Extension/Extension.entitlements"):
    kind = "sourcecode.swift" if path.endswith(".swift") else "text.xcconfig" if path.endswith(".xcconfig") else "text.plist.xml"
    files[path] = obj(path, f"isa = PBXFileReference; lastKnownFileType = {kind}; path = {quoted(path)}; sourceTree = SOURCE_ROOT;")

products = []
targets = []
for target, product_type, extension in (("DiPlaySiriProbe", "application", "app"),
                                       ("PowerIntents", "app-extension", "appex")):
    folder = "App" if target == "DiPlaySiriProbe" else "Extension"
    wrapper = "wrapper.application" if folder == "App" else "wrapper.app-extension"
    product = obj(target + "Product", f"isa = PBXFileReference; explicitFileType = {wrapper}; includeInIndex = 0; path = {target}.{extension}; sourceTree = BUILT_PRODUCTS_DIR;")
    products.append(product)
    sources = []
    for path in ("Shared/ProbeStore.swift", "App/ProbeApp.swift" if folder == "App" else "Extension/IntentHandler.swift"):
        sources.append(obj(target + path, f"isa = PBXBuildFile; fileRef = {files[path]};"))
    phases = [obj(target + "Sources", f"isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = {array(sources)}; runOnlyForDeploymentPostprocessing = 0;"),
              obj(target + "Frameworks", "isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;"),
              obj(target + "Resources", "isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;")]
    dependencies = []
    if folder == "App":
        embed = obj("EmbedExtension", f"isa = PBXBuildFile; fileRef = {ident('PowerIntentsProduct')}; settings = {{ATTRIBUTES = (RemoveHeadersOnCopy, ); }};")
        phases.append(obj("EmbedExtensions", f"isa = PBXCopyFilesBuildPhase; buildActionMask = 2147483647; dstPath = \"\"; dstSubfolderSpec = 13; files = ({embed}, ); name = \"Embed App Extensions\"; runOnlyForDeploymentPostprocessing = 0;"))
        proxy = obj("ExtensionProxy", f"isa = PBXContainerItemProxy; containerPortal = {ident('Project')}; proxyType = 1; remoteGlobalIDString = {ident('PowerIntents')}; remoteInfo = PowerIntents;")
        dependencies.append(obj("ExtensionDependency", f"isa = PBXTargetDependency; target = {ident('PowerIntents')}; targetProxy = {proxy};"))
    configs = []
    for config in ("Debug", "Release"):
        settings = {"PRODUCT_NAME": target, "PRODUCT_BUNDLE_IDENTIFIER": "$(SIRI_PROBE_BUNDLE_PREFIX)" + (".PowerIntents" if folder != "App" else ""),
                    "INFOPLIST_FILE": f"{folder}/Info.plist", "CODE_SIGN_ENTITLEMENTS": f"{folder}/{folder}.entitlements",
                    "GENERATE_INFOPLIST_FILE": "NO", "SDKROOT": "iphoneos", "TARGETED_DEVICE_FAMILY": "1,2",
                    "SWIFT_OPTIMIZATION_LEVEL": "-Onone" if config == "Debug" else "-O",
                    "LD_RUNPATH_SEARCH_PATHS": "$(inherited) @executable_path/Frameworks @executable_path/../../Frameworks"}
        if folder != "App":
            settings.update(APPLICATION_EXTENSION_API_ONLY="YES", SKIP_INSTALL="YES")
        body = " ".join(f"{k} = {quoted(v)};" for k, v in settings.items())
        configs.append(obj(target + config, f"isa = XCBuildConfiguration; baseConfigurationReference = {files['Config.xcconfig']}; buildSettings = {{{body}}}; name = {config};"))
    config_list = obj(target + "Configs", f"isa = XCConfigurationList; buildConfigurations = {array(configs)}; defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;")
    targets.append(obj(target, f"isa = PBXNativeTarget; buildConfigurationList = {config_list}; buildPhases = {array(phases)}; buildRules = (); dependencies = {array(dependencies)}; name = {target}; productName = {target}; productReference = {product}; productType = com.apple.product-type.{product_type};"))

product_group = obj("Products", f"isa = PBXGroup; children = {array(products)}; name = Products; sourceTree = \"<group>\";")
main_group = obj("MainGroup", f"isa = PBXGroup; children = {array(list(files.values()) + [product_group])}; sourceTree = \"<group>\";")
project_configs = [obj("Project" + c, f"isa = XCBuildConfiguration; buildSettings = {{CLANG_ENABLE_MODULES = YES; }}; name = {c};") for c in ("Debug", "Release")]
project_config_list = obj("ProjectConfigs", f"isa = XCConfigurationList; buildConfigurations = {array(project_configs)}; defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;")
obj("Project", f"isa = PBXProject; attributes = {{LastUpgradeCheck = 1600; }}; buildConfigurationList = {project_config_list}; compatibilityVersion = \"Xcode 14.0\"; developmentRegion = es; hasScannedForEncodings = 0; knownRegions = (es, en, Base); mainGroup = {main_group}; productRefGroup = {product_group}; projectDirPath = \"\"; projectRoot = \"\"; targets = {array(targets)};")
project = ROOT / "DiPlaySiriProbe.xcodeproj"
project.mkdir(exist_ok=True)
(project / "project.pbxproj").write_text("// !$*UTF8*$!\n{archiveVersion = 1; classes = {}; objectVersion = 56; objects = {\n" +
    "\n".join(f"{key} = {{{value}}};" for key, value in objects.items()) + f"\n}}; rootObject = {ident('Project')}; }}\n")
schemes = project / "xcshareddata/xcschemes"
schemes.mkdir(parents=True, exist_ok=True)
reference = f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{ident("DiPlaySiriProbe")}" BuildableName="DiPlaySiriProbe.app" BlueprintName="DiPlaySiriProbe" ReferencedContainer="container:DiPlaySiriProbe.xcodeproj"/>'
(schemes / "DiPlaySiriProbe.xcscheme").write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1600" version="1.3">
 <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{reference}</BuildActionEntry></BuildActionEntries></BuildAction>
 <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="NO"><BuildableProductRunnable runnableDebuggingMode="0">{reference}</BuildableProductRunnable></LaunchAction>
 <ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{reference}</BuildableProductRunnable></ProfileAction>
 <AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
''')
print("Generated DiPlaySiriProbe.xcodeproj, app/extension plists and entitlements.")
