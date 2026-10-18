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

#include <main_window/dock/lvl_props_box.h>
#include <editing/_scenes/level/lvl_history_manager.h>

#include <ui_mainwindow.h>
#include "mainwindow.h"

//Open Level Properties
void MainWindow::on_actionLevelProp_triggered(bool checked)
{
    if (checked) {
        dock_LevelProps->show();
        dock_LevelProps->raise();
    } else {
        dock_LevelProps->hide();
    }
}
