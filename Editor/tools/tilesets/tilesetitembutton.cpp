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

#include <common_features/items.h>
#include <common_features/graphics_funcs.h>
#include <common_features/util.h>

#include <defines.h>

#include <common_features/main_window_ptr.h>

#include "main_window/dock/tileset_item_box.h"
#include "tilesetitembutton.h"

TilesetItemButton::TilesetItemButton(DataConfig *conf, QGraphicsScene *scene, QWidget *parent) :
    QFrame(parent)
{
    scn = scene;
    m_id = 0;
    m_itemType = ItemTypes::LVL_Block;
    m_config = conf;
    setFrameStyle(QFrame::Panel | QFrame::Raised);
    setLineWidth(2);
}

DataConfig *TilesetItemButton::config() const
{
    return m_config;
}

void TilesetItemButton::setConfig(DataConfig *config)
{
    m_config = config;
}

void TilesetItemButton::applyItem(const int &type_i, const int &id, const int &width, const int &height, const bool notInSearch)
{
    int wid = (width == -1 ? contentsRect().width() : width);
    int hei = (height == -1 ? contentsRect().height() : height);
    QPixmap p;
    Items::getItemGFX(type_i, id, p, scn, false, QSize(wid, hei));
    setToolTip(Items::getTilesetToolTip(type_i, id, scn));

    if (notInSearch) {
        QImage image = p.toImage();
        QImage grayImage = image.convertToFormat(QImage::Format_Grayscale8);
        p = QPixmap::fromImage(grayImage);
    }

    if(p.isNull())
    {
        m_drawItem = QPixmap(wid, hei);
        return;
    }
    m_drawItem = p;
    m_id = (unsigned int)id;
    m_itemType = static_cast<ItemTypes::itemTypes>(type_i);
}

void TilesetItemButton::applySize(const int &width, const int &height)
{
    setMinimumSize(width+lineWidth()*2,height+lineWidth()*2);
    setMaximumSize(width+lineWidth()*2,height+lineWidth()*2);
}

void TilesetItemButton::paintEvent(QPaintEvent *ev)
{
    QPainter painter;
    painter.begin(this);
    painter.fillRect(contentsRect(), MainWinConnect::pMainWin->dock_TilesetBox->tileIsFavorite(m_itemType, m_id) ? Qt::darkCyan : qApp->palette().mid());
    //painter.fillRect(contentsRect(), Qt::darkGray);

    if(!m_drawItem.isNull())
        painter.drawPixmap(contentsRect(),m_drawItem,m_drawItem.rect());

    painter.end();

    QFrame::paintEvent(ev);
}

void TilesetItemButton::mousePressEvent(QMouseEvent *ev)
{
    if(isItemSet()) {
        if (ev->button() == Qt::LeftButton) {
            emit leftClicked(static_cast<int>(m_itemType), (unsigned long)m_id);
            setFrameStyle(QFrame::Panel | QFrame::Sunken);
        } else if (ev->button() == Qt::RightButton) {
            emit rightClicked(ev->globalPos(), this);
        }
    }
}

void TilesetItemButton::mouseReleaseEvent(QMouseEvent *)
{
    setFrameStyle(QFrame::Panel | QFrame::Raised);
}
unsigned int TilesetItemButton::id() const
{
    return m_id;
}

bool TilesetItemButton::isItemSet()
{
    return !m_drawItem.isNull() && m_id!=0;
}

ItemTypes::itemTypes TilesetItemButton::itemType() const
{
    return m_itemType;
}


