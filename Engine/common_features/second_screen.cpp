/*
 * Moondust, a free game engine for platform game making
 * Copyright (c) 2014-2023 Vitaly Novichkov <admin@wohlnet.ru>
 *
 * This software is licensed under a dual license system (MIT or GPL version 3 or later).
 * This means you are free to choose with which of both licenses (MIT or GPL version 3 or later)
 * you want to use this software.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You can see text of MIT license in the LICENSE.mit file you can see in Engine folder,
 * or see https://mit-license.org/.
 *
 * You can see text of GPLv3 license in the LICENSE.gpl3 file you can see in Engine folder,
 * or see <http://www.gnu.org/licenses/>.
 */

#include "second_screen.h"

#ifdef __ANDROID__
#include <settings/global_settings.h>
#include <data_configs/config_manager.h>
#include <SDL2/SDL.h>
#include <jni.h>
#include <atomic>
#include <mutex>

namespace
{

std::mutex          g_lock;
SecondScreen::Mode  g_mode = SecondScreen::MODE_TITLE;
std::string         g_title;
std::string         g_subtitle;
std::string         g_commonGfxDir;
std::string         g_fontsDir;
SecondScreen::Stats g_stats;
//! Bumped on every change, so the Java side redraws only when needed
int                 g_revision = 0;
//! Set by Java when a presentation display is attached and the HUD window is shown
std::atomic<bool>   g_displayAttached(false);

bool sameStats(const SecondScreen::Stats &a, const SecondScreen::Stats &b)
{
    return a.lives == b.lives && a.coins == b.coins && a.stars == b.stars &&
           a.points == b.points && a.health == b.health && a.characterID == b.characterID;
}

void pushKey(SDL_Keycode key)
{
    SDL_Event ev;
    SDL_memset(&ev, 0, sizeof(ev));
    ev.type = SDL_KEYDOWN;
    ev.key.state = SDL_PRESSED;
    ev.key.keysym.sym = key;
    ev.key.keysym.scancode = SDL_GetScancodeFromKey(key);
    SDL_PushEvent(&ev);
    ev.type = SDL_KEYUP;
    ev.key.state = SDL_RELEASED;
    SDL_PushEvent(&ev);
}

} // namespace

void SecondScreen::publish(Mode mode, const std::string &title, const std::string &subtitle, const Stats &stats)
{
    std::lock_guard<std::mutex> guard(g_lock);
    if(g_mode == mode && g_title == title && g_subtitle == subtitle &&
       sameStats(g_stats, stats) && g_commonGfxDir == ConfigManager::PathCommonGFX())
        return;
    g_mode = mode;
    g_title = title;
    g_subtitle = subtitle;
    g_stats = stats;
    g_commonGfxDir = ConfigManager::PathCommonGFX();
    g_fontsDir = ConfigManager::config_dirSTD + "/fonts/";
    g_revision++;
}

bool SecondScreen::hudOnSecondScreen()
{
    return g_AppSettings.secondScreenHud && g_displayAttached.load();
}

extern "C"
{

JNIEXPORT void JNICALL
Java_ru_wohlsoft_moondust_SecondScreen_nativeSetDisplayAttached(JNIEnv *, jclass, jboolean attached)
{
    g_displayAttached.store(attached == JNI_TRUE);
}

/**
 * Returns { enabled, mode, revision, lives, coins, stars, points, health, characterID }
 */
JNIEXPORT jintArray JNICALL
Java_ru_wohlsoft_moondust_SecondScreen_nativeGetState(JNIEnv *env, jclass)
{
    jint out[9];
    {
        std::lock_guard<std::mutex> guard(g_lock);
        out[0] = g_AppSettings.secondScreenHud ? 1 : 0;
        out[1] = static_cast<jint>(g_mode);
        out[2] = g_revision;
        out[3] = g_stats.lives;
        out[4] = g_stats.coins;
        out[5] = g_stats.stars;
        out[6] = g_stats.points;
        out[7] = g_stats.health;
        out[8] = g_stats.characterID;
    }
    jintArray arr = env->NewIntArray(9);
    if(arr)
        env->SetIntArrayRegion(arr, 0, 9, out);
    return arr;
}

/**
 * Returns { title, subtitle, commonGFX folder, fonts folder, playable character name }
 */
JNIEXPORT jobjectArray JNICALL
Java_ru_wohlsoft_moondust_SecondScreen_nativeGetTexts(JNIEnv *env, jclass)
{
    std::string texts[5];
    {
        std::lock_guard<std::mutex> guard(g_lock);
        texts[0] = g_title;
        texts[1] = g_subtitle;
        texts[2] = g_commonGfxDir;
        texts[3] = g_fontsDir;
        texts[4] = ConfigManager::playable_characters.contains(g_stats.characterID) ?
                   ConfigManager::playable_characters[g_stats.characterID].name : std::string();
    }
    jobjectArray arr = env->NewObjectArray(5, env->FindClass("java/lang/String"), nullptr);
    if(!arr)
        return nullptr;
    for(int i = 0; i < 5; i++)
    {
        jstring s = env->NewStringUTF(texts[i].c_str());
        env->SetObjectArrayElement(arr, i, s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

//! The "Pause" button on the second screen: same as pressing Escape
JNIEXPORT void JNICALL
Java_ru_wohlsoft_moondust_SecondScreen_nativePressPause(JNIEnv *, jclass)
{
    pushKey(SDLK_ESCAPE);
}

} // extern "C"

#else // __ANDROID__

void SecondScreen::publish(Mode, const std::string &, const std::string &, const Stats &)
{}

bool SecondScreen::hudOnSecondScreen()
{
    return false;
}

#endif // __ANDROID__
