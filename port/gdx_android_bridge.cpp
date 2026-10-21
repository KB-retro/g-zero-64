// port/gdx_android_bridge.cpp -- G-Zero 64: the Android quick menu's access to game settings.
//
// The quick menu (android/.../QuickMenuDialog.java) runs on the Android UI thread, while CVars and
// the window belong to the game thread. Reads and writes are therefore queued here and served by
// gdx_android_bridge_tick(), which the game thread calls once per frame (GdxFast3dGui). A read waits
// briefly for that tick; a write is applied, with any live side effect, on the next one.
#ifdef __ANDROID__
#include <jni.h>

#include <chrono>
#include <atomic>
#include <condition_variable>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "fast/Fast3dWindow.h"
#include "libultraship/bridge/consolevariablebridge.h"
#include "ship/Context.h"
#include "ship/window/Window.h"
#include "ship/window/gui/Gui.h"

#include "gdx_menu.h"

namespace {

// Pseudo-setting: the FPS counter is a GUI window, not a CVar.
constexpr const char* kFpsCounter = "gz64.FpsCounter";

struct Request {
    std::string name;
    bool isFloat = false;
    float value = 0.0f; // default on the way in, result on the way out
    bool done = false;
};

std::mutex sMutex;
std::condition_variable sServed;
std::vector<Request> sWrites;
std::vector<Request*> sReads;

// Work the quick menu needs done on the game thread, waited for by the caller.
struct Job {
    std::function<void()> work;
    bool done = false;
};
std::vector<Job*> sJobs;

// While the Android quick menu is open the main loop holds the game still (gdx_android_menu_open).
std::atomic<bool> sMenuOpen{ false };

std::shared_ptr<GdxMenu> Menu() {
    auto window = Ship::Context::GetInstance()->GetWindow();
    auto gui = window != nullptr ? window->GetGui() : nullptr;
    return gui != nullptr ? std::dynamic_pointer_cast<GdxMenu>(gui->GetMenu()) : nullptr;
}

// Runs work on the game thread and waits up to timeoutMs for it. False if it did not run.
bool RunOnGameThread(std::function<void()> work, int timeoutMs) {
    Job job{ std::move(work) };
    std::unique_lock<std::mutex> lock(sMutex);
    sJobs.push_back(&job);
    if (sServed.wait_for(lock, std::chrono::milliseconds(timeoutMs), [&] { return job.done; })) {
        return true;
    }
    for (auto it = sJobs.begin(); it != sJobs.end(); ++it) {
        if (*it == &job) {
            sJobs.erase(it);
            break;
        }
    }
    return job.done;
}

std::shared_ptr<Ship::GuiWindow> FpsWindow() {
    auto window = Ship::Context::GetInstance()->GetWindow();
    auto gui = window != nullptr ? window->GetGui() : nullptr;
    return gui != nullptr ? gui->GetGuiWindow("FPS Counter") : nullptr;
}

float Read(const Request& r) {
    if (r.name == kFpsCounter) {
        auto fps = FpsWindow();
        return fps != nullptr && fps->IsVisible() ? 1.0f : 0.0f;
    }
    if (r.isFloat) {
        return CVarGetFloat(r.name.c_str(), r.value);
    }
    return static_cast<float>(CVarGetInteger(r.name.c_str(), static_cast<int32_t>(r.value)));
}

void Apply(const Request& r) {
    if (r.name == kFpsCounter) {
        auto fps = FpsWindow();
        if (fps != nullptr && fps->IsVisible() != (r.value != 0.0f)) {
            fps->ToggleVisibility();
        }
        return;
    }
    if (r.isFloat) {
        CVarSetFloat(r.name.c_str(), r.value);
    } else {
        CVarSetInteger(r.name.c_str(), static_cast<int32_t>(r.value));
    }
    // The settings the menu changes that also need the renderer told, as the desktop menu does.
    auto window = Ship::Context::GetInstance()->GetWindow();
    if (window == nullptr) {
        return;
    }
    if (r.name == "gMSAAValue") {
        window->SetMsaaLevel(static_cast<uint32_t>(r.value));
    } else if (r.name == "gInternalResolution") {
        window->SetResolutionMultiplier(r.value);
    } else if (r.name == "gTextureFilter") {
        auto fast = std::dynamic_pointer_cast<Fast::Fast3dWindow>(window);
        if (fast != nullptr) {
            fast->SetTextureFilter(static_cast<Fast::FilteringMode>(static_cast<int>(r.value)));
        }
    }
}

std::string Utf(JNIEnv* env, jstring s) {
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(s, chars);
    }
    return out;
}

} // namespace

extern "C" int gdx_android_menu_open(void) {
    return sMenuOpen.load() ? 1 : 0;
}

extern "C" void gdx_android_bridge_tick(void) {
    std::vector<Request> writes;
    {
        std::lock_guard<std::mutex> lock(sMutex);
        writes.swap(sWrites);
        for (Request* r : sReads) {
            r->value = Read(*r);
            r->done = true;
        }
        sReads.clear();
        // Jobs run under the lock: a waiting caller only reads its own job after done is set.
        for (Job* job : sJobs) {
            job->work();
            job->done = true;
        }
        sJobs.clear();
    }
    sServed.notify_all();
    for (const Request& r : writes) {
        Apply(r);
    }
    if (!writes.empty()) {
        CVarSave();
    }
}

// Reads several settings in one game frame. isFloat[i] picks the CVar type; defaults[i] is used
// for an unset CVar and returned unchanged if the game does not answer within half a second.
extern "C" JNIEXPORT jfloatArray JNICALL Java_org_patrullaretro_gzero64_GameBridge_nativeGet(
    JNIEnv* env, jclass, jobjectArray names, jbooleanArray isFloat, jfloatArray defaults) {
    const jsize n = env->GetArrayLength(names);
    std::vector<Request> requests(static_cast<size_t>(n));
    jboolean* floats = env->GetBooleanArrayElements(isFloat, nullptr);
    jfloat* defs = env->GetFloatArrayElements(defaults, nullptr);
    for (jsize i = 0; i < n; i++) {
        auto name = static_cast<jstring>(env->GetObjectArrayElement(names, i));
        requests[i].name = Utf(env, name);
        env->DeleteLocalRef(name);
        requests[i].isFloat = floats[i] != JNI_FALSE;
        requests[i].value = defs[i];
    }
    env->ReleaseBooleanArrayElements(isFloat, floats, JNI_ABORT);
    {
        std::unique_lock<std::mutex> lock(sMutex);
        for (Request& r : requests) {
            sReads.push_back(&r);
        }
        const bool served = sServed.wait_for(lock, std::chrono::milliseconds(500), [&] {
            for (const Request& r : requests) {
                if (!r.done) {
                    return false;
                }
            }
            return true;
        });
        if (!served) {
            // No frame ran (game paused or still booting): withdraw, keep the defaults.
            for (Request& r : requests) {
                for (auto it = sReads.begin(); it != sReads.end(); ++it) {
                    if (*it == &r) {
                        sReads.erase(it);
                        break;
                    }
                }
                if (!r.done) {
                    r.value = defs[&r - requests.data()];
                }
            }
        }
    }
    for (jsize i = 0; i < n; i++) {
        defs[i] = requests[i].value;
    }
    jfloatArray out = env->NewFloatArray(n);
    env->SetFloatArrayRegion(out, 0, n, defs);
    env->ReleaseFloatArrayElements(defaults, defs, JNI_ABORT);
    return out;
}

extern "C" JNIEXPORT void JNICALL Java_org_patrullaretro_gzero64_GameBridge_nativeSet(JNIEnv* env, jclass,
                                                                                      jstring name, jboolean isFloat,
                                                                                      jfloat value) {
    Request r;
    r.name = Utf(env, name);
    r.isFloat = isFloat != JNI_FALSE;
    r.value = value;
    std::lock_guard<std::mutex> lock(sMutex);
    sWrites.push_back(std::move(r));
}

extern "C" JNIEXPORT void JNICALL Java_org_patrullaretro_gzero64_GameBridge_nativeSetMenuOpen(JNIEnv*, jclass,
                                                                                             jboolean open) {
    sMenuOpen.store(open != JNI_FALSE);
}

// The desktop menu's registry as JSON (gdx_menu_bridge.cpp); "" if the game did not answer.
extern "C" JNIEXPORT jstring JNICALL Java_org_patrullaretro_gzero64_GameBridge_nativeMenuJson(JNIEnv* env, jclass) {
    std::string json;
    RunOnGameThread(
        [&json] {
            auto menu = Menu();
            if (menu != nullptr) {
                json = menu->BridgeExportJson();
            }
        },
        1500);
    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL Java_org_patrullaretro_gzero64_GameBridge_nativeMenuSet(JNIEnv* env, jclass,
                                                                                            jstring id, jdouble value) {
    const std::string key = Utf(env, id);
    bool ok = false;
    RunOnGameThread(
        [&] {
            auto menu = Menu();
            ok = menu != nullptr && menu->BridgeSet(key, value);
        },
        1500);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_org_patrullaretro_gzero64_GameBridge_nativeMenuPress(JNIEnv* env, jclass,
                                                                                              jstring id) {
    const std::string key = Utf(env, id);
    bool ok = false;
    RunOnGameThread(
        [&] {
            auto menu = Menu();
            ok = menu != nullptr && menu->BridgePress(key);
        },
        1500);
    return ok ? JNI_TRUE : JNI_FALSE;
}
#endif
