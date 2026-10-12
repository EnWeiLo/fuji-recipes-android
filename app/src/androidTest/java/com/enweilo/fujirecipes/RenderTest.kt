package com.enweilo.fujirecipes

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.enweilo.fujirecipes.polaroid.Card
import com.enweilo.fujirecipes.polaroid.Fonts
import com.enweilo.fujirecipes.polaroid.Frame
import com.enweilo.fujirecipes.polaroid.Logos
import com.enweilo.fujirecipes.polaroid.Meta
import com.enweilo.fujirecipes.polaroid.PState
import com.enweilo.fujirecipes.polaroid.Ratio
import com.enweilo.fujirecipes.polaroid.Src
import com.enweilo.fujirecipes.polaroid.stateFromMeta
import org.junit.Test
import org.junit.runner.RunWith

/** Draws a set of cards with the real renderer so the result can be looked at (the CI job collects the pictures). */
@RunWith(AndroidJUnit4::class)
class RenderTest {

    @Test
    fun renderCards() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        Fonts.init(ctx)
        Logos.init(ctx)
        val photo = inst.context.assets.open("sample.jpg").use { BitmapFactory.decodeStream(it) }

        val sony = stateFromMeta(
            Meta("SONY", "ILCE-7CM2", "FE 35mm F1.8", "400", 1.8, 1.0 / 250, "2026:09:07 12:00:00"),
        ).copy(noteText = "台北・週末散步 Weekend walk", dateOn = true, showExp = true)
        val nikon = stateFromMeta(
            Meta("NIKON CORPORATION", "NIKON Z5_2", "NIKKOR Z 40mm f/2 SE", "100", 2.0, 1.0 / 500, "2026:09:08 09:30:00"),
        ).copy(noteText = "Z5 II test", dateOn = true)
        val fuji = stateFromMeta(
            Meta("FUJIFILM", "X-T50", "XF35mmF1.4 R", "200", 1.4, 1.0 / 125, "2026:09:09 18:20:00"),
        ).copy(noteText = "X-T50", dateOn = true, showExp = true)
        val z6 = stateFromMeta(Meta("NIKON CORPORATION", "NIKON Z 6", "", "800", 4.0, 1.0 / 60, "")).copy(dateOn = false)
        val phone = stateFromMeta(Meta("Apple", "iPhone 15 Pro", "", "50", 1.8, 1.0 / 120, "2026:10:01 08:00:00"))
            .copy(noteText = "iPhone", dateOn = true, showCam = true)

        val cases = linkedMapOf<String, PState>()
        cases["01_white_orig"] = sony
        cases["02_black_mini"] = sony.copy(frame = Frame.Black, ratio = Ratio.Mini, color = Frame.Black.textColor)
        cases["03_tint_square"] = sony.copy(frame = Frame.Tint, ratio = Ratio.Square, color = Frame.Tint.textColor)
        cases["04_vintage_wide"] = sony.copy(frame = Frame.Vintage, ratio = Ratio.Wide, color = Frame.Vintage.textColor, dateRight = true)
        cases["05_film_orig"] = sony.copy(frame = Frame.Film, color = Frame.Film.textColor)
        cases["06_banner_white"] = sony.copy(ratio = Ratio.Banner)
        cases["07_banner_black"] = nikon.copy(ratio = Ratio.Banner, frame = Frame.Black, color = Frame.Black.textColor)
        cases["08_banner_film_big"] = fuji.copy(ratio = Ratio.Banner, frame = Frame.Film, color = Frame.Film.textColor, sizeLeft = 22)
        cases["09_insta"] = sony.copy(ratio = Ratio.Insta)
        cases["10_story"] = sony.copy(ratio = Ratio.Story, frame = Frame.Black, color = Frame.Black.textColor)
        cases["11_nikon_z5ii"] = nikon
        cases["12_fuji_xt50"] = fuji
        cases["13_nikon_z6"] = z6
        cases["14_phone"] = phone.copy(font = "hand")
        cases["15_manual_none"] = sony.copy(brandChoice = Src.Manual, manualBrand = "Leica", modelChoice = Src.Manual, manualModel = "Q3", lensChoice = Src.None)
        cases["16_zoomed_crop"] = sony.copy(cropZ = 2f, cropX = -120f, cropY = 60f, datePos = 60f)
        for (f in Fonts.choices) {
            cases["font_" + f.key] = sony.copy(font = f.key, noteFont = f.key)
        }

        val resolver = ctx.contentResolver
        for ((name, st) in cases) {
            val bmp = Card.render(st, photo)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/rendercheck")
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            resolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bmp.recycle()
        }
    }
}
