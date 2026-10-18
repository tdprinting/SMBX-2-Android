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

#include <PGE_File_Formats/lvl_filedata.h>
#include <common_features/json_settings_widget.h>

#include <ui_mainwindow.h>
#include <mainwindow.h>
#include <QLineEdit>

#include "lvl_props_box.h"
#include <ui_lvl_props_box.h>
#include <editing/_dialogs/savingnotificationdialog.h>


LevelProps::LevelProps(QWidget *parent) :
    QDockWidget(parent),
    MWDock_Base(parent),
    ui(new Ui::LevelProps)
{
    setVisible(false);
    setAttribute(Qt::WA_ShowWithoutActivating);
    ui->setupUi(this);

    mw()->addDockWidget(Qt::RightDockWidgetArea, this);
    connect(mw(), SIGNAL(languageSwitched()), this, SLOT(re_translate()));

    connect(this, SIGNAL(visibilityChanged(bool)), mw()->ui->actionLevelProp, SLOT(setChecked(bool)));
    connect(mw(), SIGNAL(setSMBX64Strict(bool)),
            this, SLOT(setSMBX64Strict(bool)));
    m_lastVisibilityState = isVisible();
    mw()->docks_level.addState(this, &m_lastVisibilityState);
    if(parent)
    {
        if(std::strcmp(parent->metaObject()->className(), "MainWindow") == 0)
            m_mw = qobject_cast<MainWindow *>(parent);
        else
            m_mw = nullptr;
    }
}

void LevelProps::loadData(LevelData &FileData) {
    m_currentData = &FileData;
    ui->LevelTitle->setText(m_currentData->LevelName);
    initAdvancedSettings();
}

LevelProps::~LevelProps()
{
    if(m_extraSettings.get())
        ui->frame->layout()->removeWidget(m_extraSettings.get()->getWidget());
    m_extraSettings.reset();
    delete ui;
}

void LevelProps::on_LevelTitle_editingFinished() {
    m_levelTitle = ui->LevelTitle->text().trimmed().simplified().remove('\"');

    if (m_currentData == nullptr) {
        return;
    }

    m_currentData->LevelName = m_levelTitle;
    m_currentData->meta.modified = true;
    LevelEdit* e = mw()->activeLvlEditWin();
    e->setWindowTitle(QString(m_levelTitle.isEmpty() ? e->userFriendlyCurrentFile() : m_levelTitle).replace("&", "&&&"));
    mw()->updateWindowMenu();
}

void LevelProps::setSMBX64Strict(bool en)
{
    DataConfig &c = mw()->configs;

    ui->advancedSettings->setEnabled(!en);
}

void LevelProps::re_translate()
{
    ui->retranslateUi(this);
    //initDefaults();
}

void LevelProps::focusInEvent(QFocusEvent *ev)
{
    QDockWidget::focusInEvent(ev);
    //ev->accept();
    //qApp->setActiveWindow(mw());
}

void LevelProps::initAdvancedSettings()
{
    QString defaultDir = m_mw->configs.config_dir;
    CustomDirManager uLVL(m_currentData->meta.path, m_currentData->meta.filename);
    uLVL.setDefaultDir(defaultDir);

    QString esLayoutFile = uLVL.getCustomFile("lvl_settings.json");
    if(esLayoutFile.isEmpty())
        return;

    QFile layoutFile(esLayoutFile);
    if(!layoutFile.open(QIODevice::ReadOnly))
        return;

    QByteArray rawLayout = layoutFile.readAll();

    ui->frame->setEnabled(!m_currentData->meta.smbx64strict);

    if(m_extraSettings.get())
        ui->advancedSettings->layout()->removeWidget(m_extraSettings.get()->getWidget());

    m_extraSettings.reset(new JsonSettingsWidget(ui->frame));

    if(m_extraSettings)
    {
        m_extraSettings->setSearchDirectories(m_currentData->meta.path, m_currentData->meta.filename);
        m_extraSettings->setConfigPack(&m_mw->configs);
        if(!m_extraSettings->loadLayout(m_currentData->custom_params.toUtf8(), rawLayout))
        {
            LogWarning(m_extraSettings->errorString());
            ui->noSettings->setText(tr("Error in the file %1:\n%2")
                                          .arg(esLayoutFile)
                                          .arg(m_extraSettings->errorString()));
            ui->noSettings->setStyleSheet("*{background-color: #FF0000;}");
        }
        auto *widget = m_extraSettings->getWidget();
        if(widget)
        {
            ui->frame->layout()->addWidget(widget);
            JsonSettingsWidget::connect(m_extraSettings.get(), &JsonSettingsWidget::settingsChanged, this, &LevelProps::onExtraSettingsChanged);
            ui->noSettings->setVisible(false);
        }
    }
    layoutFile.close();
}

void LevelProps::onExtraSettingsChanged()
{
    m_customParams = m_extraSettings->saveSettings();
    LogDebug("Extra Settings change: " + m_customParams);
    m_currentData->custom_params = m_customParams;
    m_currentData->meta.modified = true;
}
