pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "KitchenDesigner"
include(":app")
// Unity-as-a-Library module is added here once exported from Unity (see unity/README.md):
// include(":unityLibrary")
