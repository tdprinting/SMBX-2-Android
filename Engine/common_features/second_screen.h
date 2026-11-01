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

#ifndef SECOND_SCREEN_H
#define SECOND_SCREEN_H

#include <string>

/**
 * @brief HUD for a second display (Android dual-screen devices such as the AYN Thor).
 *
 * Scenes publish a snapshot of what the HUD shows; the Java side (ru.wohlsoft.moondust.SecondScreen)
 * polls it and draws it on the second display with the config pack's own graphics and fonts.
 * On other platforms every call is a no-op.
 */
namespace SecondScreen
{

enum Mode
{
    MODE_TITLE = 0,
    MODE_WORLD = 1,
    MODE_LEVEL = 2
};

struct Stats
{
    int lives = 0;
    int coins = 0;
    int stars = 0;
    int points = 0;
    int health = 0;
    int characterID = 1;
};

/**
 * @brief Publishes the current scene and the HUD counters
 * @param mode Current scene type
 * @param title Episode title on the world map, level title in levels
 * @param subtitle Level under the cursor on the world map, empty otherwise
 * @param stats HUD counters
 */
void publish(Mode mode, const std::string &title, const std::string &subtitle = std::string(),
             const Stats &stats = Stats());

/**
 * @brief Is the HUD currently shown on the second display? Scenes skip their own HUD then.
 */
bool hudOnSecondScreen();

}

#endif // SECOND_SCREEN_H
