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

#ifndef OBJ_BASEITEM_H
#define OBJ_BASEITEM_H

#include <QString>
#include <QPixmap>
#include <stdint.h>
#include <ConfigPackManager/level/config_baseitem.h>

template <typename T>

struct obj_baseitem
{
    bool isValid = false;
    unsigned long animator_id = 0;
    QPixmap  *cur_image = nullptr;
    QPixmap image;
    QPixmap  *cur_icon = nullptr;
    QPixmap icon;

    T setup;
protected:
    void copyBase(obj_baseitem<T> &item)
    {
        static_assert(std::is_base_of<BaseSetup, T>::value, "type parameter of this class must derive from BaseSetup");
        /* for internal usage */
        item.isValid         = isValid;
        item.animator_id     = animator_id;
        item.cur_image       = cur_image;
        item.cur_icon        = cur_icon;

        if(!cur_image)
            item.cur_image   = &image;
        if(!cur_icon)
            item.cur_icon    = &icon;

        item.setup = setup;
    }
};

#endif // OBJ_BASEITEM_H
