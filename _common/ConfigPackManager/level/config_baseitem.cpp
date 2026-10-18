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

#include "config_baseitem.h"

#include <IniProcessor/ini_processing.h>
#include <Utils/maths.h>
#include "../image_size.h"

#include <assert.h>

bool BaseSetup::baseParse(IniProcessing *setup,
                     PGEString bgoImgPath,
                     uint32_t /*defaultGrid*/,
                     const BaseSetup *merge_with,
                     PGEString *error)
{
#define pMerge(param, def) (merge_with ? pgeConstReference(merge_with->param) : pgeConstReference(def))
#define pMergeMe(param) (merge_with ? pgeConstReference(merge_with->param) : pgeConstReference(param))
#define pAlias(paramName, destValue) setup->read(paramName, destValue, destValue)

    int errCode = PGE_ImageInfo::ERR_OK;
    PGEString section;
    /*************Buffers*********************/
    w = 0,
    h = 0;
    /*************Buffers*********************/
    if(!setup)
    {
        if(error)
            *error = "setup IniProcessing is null!";
        return false;
    }

    section = StdToPGEString(setup->group());
    setup->read("name", name, pMerge(name, section));

    if(name.size() == 0)
    {
        if(error)
            *error = section + ": item name isn't defined";
        return false;
    }

    setup->read("group",    group, pMergeMe(group));
    setup->read("category", category, pMergeMe(category));
    setup->read("description", description, pMerge(description, ""));
    setup->read("extra-settings", extra_settings, pMerge(extra_settings, ""));
    setup->read("is-meta-object", is_meta_object, pMerge(is_meta_object, false));

    setup->read("image",    image_n, pMerge(image_n, ""));
#ifdef PGE_EDITOR // alternative image for Editor
    pAlias("editor-image", image_n);
#endif

    if(!merge_with && !PGE_ImageInfo::getImageSize(bgoImgPath + image_n, &w, &h, &errCode))
    {
        if(error)
        {
            switch(errCode)
            {
            case PGE_ImageInfo::ERR_UNSUPPORTED_FILETYPE:
                *error = "Unsupported or corrupted file format: " + bgoImgPath + image_n;
                break;

            case PGE_ImageInfo::ERR_NOT_EXISTS:
                *error = "image file is not exist: " + bgoImgPath + image_n;
                break;

            case PGE_ImageInfo::ERR_CANT_OPEN:
                *error = "Can't open image file: " + bgoImgPath + image_n;
                break;
            }
        }

        return false;
    }

    if(!merge_with && ((w == 0)||(h == 0)))
    {
        if(error)
            *error = "Width or height of image has zero or negative value in image " + bgoImgPath + image_n;
        return false;
    }

    mask_n = PGE_ImageInfo::getMaskName(image_n);

    setup->read("icon", icon_n, pMerge(icon_n, ""));

#undef pMerge
#undef pMergeMe
#undef pAlias
    return true;
}
