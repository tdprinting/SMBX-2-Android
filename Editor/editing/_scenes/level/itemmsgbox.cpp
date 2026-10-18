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

#include <QFontDatabase>
#include <QFontMetrics>
#include <QScrollBar>
#include <QStyle>

#include "itemmsgbox.h"
#include <ui_itemmsgbox.h>

ItemMsgBox::ItemMsgBox(Opened_By openedBy, QString text, bool isFriendly, QString label, QString title, QWidget *parent) :
    QDialog(parent),
    ui(new Ui::ItemMsgBox)
{
    QFontDatabase::addApplicationFont(":/fonts/PressStart2P.ttf");

    whoOpened = openedBy;
    currentText = text;
    ui->setupUi(this);

    ui->checkFriendly->setChecked(isFriendly);

    if(whoOpened != NPC)
        ui->checkFriendly->hide();

    if(!label.isEmpty())
        ui->NotesLabel->setText(label);

    if(!title.isEmpty())
        this->setWindowTitle(title);

    //#ifdef Q_OS_MACX
    //    QFont theFont("Press Start 2P", 11);
    //#else
    //    QFont theFont("Press Start 2P", 11);
    //#endif
    QFont theFont("Press Start 2P");
    theFont.setPixelSize(7);
    theFont.setStyleHint(QFont::Monospace);
    ui->msgTextBoxTiny->setFont(theFont);
    ui->msgTextBox->clear();
    QFontMetrics meter(ui->msgTextBoxTiny->font());
    int w_width = meter.size(Qt::TextSingleLine, "XXXXXXXXXXXXXXXXXXXXXXXXXXX").width();
    int scrW = ui->msgTextBoxTiny->style()->pixelMetric(QStyle::PM_ScrollBarExtent);
    ui->msgTextBoxTiny->setMaximumWidth(w_width + scrW + 18);
    ui->msgTextBoxTiny->setMinimumWidth(w_width + scrW + 18);
    ui->msgTextBoxTiny->appendPlainText(currentText);
    ui->msgTextBox->appendPlainText(currentText);
    updateGeometry();
    on_msgTextBox_textChanged();
}

ItemMsgBox::~ItemMsgBox()
{
    delete ui;
}

void ItemMsgBox::on_msgTextBox_textChanged() {
    ui->msgTextBoxTiny->clear();
    ui->msgTextBoxTiny->appendPlainText(ui->msgTextBox->toPlainText().replace("\n", " "));
}

bool ItemMsgBox::isFriendlyChecked()
{
    return ui->checkFriendly->isChecked();
}

void ItemMsgBox::on_buttonBox_accepted()
{
    currentText = ui->msgTextBox->toPlainText();
    accept();
}
