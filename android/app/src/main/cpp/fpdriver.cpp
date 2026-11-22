// libfpdriver.so: loads a custom Vulkan driver (Turnip and other adrenotools packs) for native
// Flash, and hands its vkGetInstanceProcAddr to the Rust player (fp-ruffle), which creates its
// Vulkan instance through it.
//
// Loading is ARMSX3's (native-lib.cpp): libadrenotools (BSD-2-Clause, Billy Laws) with the same
// arguments, and ARMSX3's check that the driver asked for is the one that actually opened.

#include <adrenotools/driver.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <sys/stat.h>

#include <cstdio>
#include <string>
#include <string_view>

#define LOG_TAG "fpdriver"

static std::string g_error;

static std::string unwrap(JNIEnv *env, jstring s) {
  if (s == nullptr) return {};
  const char *c = env->GetStringUTFChars(s, nullptr);
  std::string out = c ? c : "";
  if (c) env->ReleaseStringUTFChars(s, c);
  return out;
}

// Whether the driver adrenotools was asked for is the driver it opened.
//
// adrenotools opens the driver only when the Vulkan loader first needs one, and when that
// fails (a library this device cannot link, a pack built against another kernel driver) it
// logs to logcat and opens the system driver instead. The load looked successful, the emulator
// logged "Vulkan driver bound (custom)", and Arm's own driver ran: a Mali-G720 user's PanVK
// pack "worked", with a log identical in every Vulkan line to a run on the system driver.
//
// So the loader is made to open its driver now, through the cheapest call that needs one, and
// the driver file is looked for among the files mapped into this process. By inode and name,
// since the same file can be reached as /data/data/<pkg> or /data/user/0/<pkg>. When the maps
// cannot be read the answer is yes, which is what every load was taken for before.
static bool custom_driver_loaded(void *loader, const std::string &soPath) {
  struct stat file {};
  if (::stat(soPath.c_str(), &file) != 0) {
    return false;
  }

  using enumerate_fn = int32_t (*)(const char *, uint32_t *, void *);
  if (auto enumerate = reinterpret_cast<enumerate_fn>(
          dlsym(loader, "vkEnumerateInstanceExtensionProperties"))) {
    uint32_t count = 0;
    enumerate(nullptr, &count, nullptr);
  }

  std::FILE *maps = std::fopen("/proc/self/maps", "re");
  if (maps == nullptr) {
    return true;
  }

  const std::string name = soPath.substr(soPath.find_last_of('/') + 1);
  bool found = false;
  char line[1024];

  while (!found && std::fgets(line, sizeof(line), maps) != nullptr) {
    // address perms offset dev inode path
    unsigned long inode = 0;
    int path_at = 0;
    if (std::sscanf(line, "%*s %*s %*s %*s %lu %n", &inode, &path_at) != 1 ||
        inode != file.st_ino) {
      continue;
    }

    std::string_view path{line + path_at};
    while (!path.empty() && (path.back() == '\n' || path.back() == ' ')) {
      path.remove_suffix(1);
    }

    found = path.size() > name.size() && path.substr(path.size() - name.size()) == name &&
            path[path.size() - name.size() - 1] == '/';
  }

  std::fclose(maps);
  return found;
}


// Returns the driver's vkGetInstanceProcAddr as a jlong, or 0 when the driver could not be loaded
// (see nativeLastError). Paths: driverDir and redirectDir end with '/'; hookDir is the app's
// nativeLibraryDir (where libadrenotools' hook libraries are installed).
extern "C" JNIEXPORT jlong JNICALL Java_com_armsx_flashpoint_data_FpDriver_nativeOpen(
    JNIEnv *env, jclass, jstring jdriverDir, jstring jlibName, jstring jredirectDir, jstring jhookDir) {
  const std::string driverDir = unwrap(env, jdriverDir);
  const std::string libName = unwrap(env, jlibName);
  const std::string redirectDir = unwrap(env, jredirectDir);
  std::string hookDir = unwrap(env, jhookDir);
  if (!hookDir.empty() && hookDir.back() != '/') hookDir += '/';

  ::dlerror();
  void *loader = adrenotools_open_libvulkan(
      RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM | (redirectDir.empty() ? 0 : ADRENOTOOLS_DRIVER_FILE_REDIRECT), nullptr,
      hookDir.c_str(), driverDir.c_str(), libName.c_str(), redirectDir.empty() ? nullptr : redirectDir.c_str(), nullptr);
  if (loader == nullptr || !custom_driver_loaded(loader, driverDir + libName)) {
    const char *e = ::dlerror();
    g_error = "Custom driver '" + libName + "' could not be loaded on this device; the system driver is used instead" +
              (e ? std::string(": ") + e : std::string("."));
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", g_error.c_str());
    return 0;
  }
  void *gipa = dlsym(loader, "vkGetInstanceProcAddr");
  if (gipa == nullptr) {
    g_error = "The driver exports no vkGetInstanceProcAddr.";
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", g_error.c_str());
    return 0;
  }
  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "custom driver loaded: %s%s", driverDir.c_str(), libName.c_str());
  // Never dlclose()d: a Vulkan driver cannot be unloaded while anything resolved from it lives.
  return reinterpret_cast<jlong>(gipa);
}

extern "C" JNIEXPORT jstring JNICALL Java_com_armsx_flashpoint_data_FpDriver_nativeLastError(JNIEnv *env, jclass) {
  return env->NewStringUTF(g_error.c_str());
}

