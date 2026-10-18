/*
 * Platformer Game Engine by Wohlstand, a free platform for game making
 * Copyright (c) 2014-2023 Vitaly Novichkov <admin@wohlnet.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

#pragma once
#ifndef OBJ_BG_H
#define OBJ_BG_H

#include <QString>
#include <QPixmap>
#include <stdint.h>
#include "obj_baseitem.h"
#include <ConfigPackManager/level/config_bg.h>

struct obj_BG : obj_baseitem<BgSetup>
{
    QPixmap * cur_image_second = nullptr;
    QPixmap second_image;
    QColor  fill_color;

    void copyTo(obj_BG &bg);
};

#endif // OBJ_BG_H

