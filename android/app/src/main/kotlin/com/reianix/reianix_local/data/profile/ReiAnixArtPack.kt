package com.reianix.reianix_local.data.profile

import android.content.Context
import java.io.InputStream

internal data class PersonalArt(val folder: String, val id: String, val title: String, val extension: String = "webp") {
    val path get() = "personalization/$folder/$id.$extension"
}

internal object ReiAnixArtPack {
    val banners = listOf(
        "chuva_neon" to "Chuva neon", "cidade_dourada" to "Cidade dourada",
        "eclipse_rubro" to "Eclipse rubro", "floresta_encantada" to "Floresta encantada",
        "montanha_nevada" to "Montanha nevada", "quarto_gamer" to "Quarto gamer",
        "ruinas_submersas" to "Ruínas submersas", "sakura_lunar" to "Sakura lunar",
        "santuario_celestial" to "Santuário celestial", "trem_de_outono" to "Trem de outono",
    ).map { PersonalArt("banners", it.first, it.second) } + listOf(
        "samurai_lua_rubra" to "Samurai da lua rubra", "ruas_neon_violeta" to "Ruas neon violeta",
        "vale_das_sakuras" to "Vale das sakuras", "lua_azul_alpina" to "Lua azul alpina",
        "eclipse_dourado" to "Eclipse dourado", "costa_estelar_turquesa" to "Costa estelar turquesa",
    ).map { PersonalArt("banners", it.first, it.second, "png") }
    val wallpapers = listOf(
        "cafe_chuvoso" to "Café chuvoso", "caminho_sakura" to "Caminho sakura",
        "cascatas_misticas" to "Cascatas místicas", "cidade_cyberpunk" to "Cidade cyberpunk",
        "costa_ao_entardecer" to "Costa ao entardecer", "crepusculo_urbano" to "Crepúsculo urbano",
        "magia_violeta" to "Magia violeta", "montanhas_estreladas" to "Montanhas estreladas",
        "vale_dos_templos" to "Vale dos templos", "viajante_lunar" to "Viajante lunar",
    ).map { PersonalArt("wallpapers", it.first, it.second) } + listOf(
        "samurai_escarlate" to "Samurai escarlate", "toquio_violeta" to "Tóquio violeta",
        "ceu_de_estrelas" to "Céu de estrelas", "manga_lunar" to "Mangá lunar",
        "santuario_esmeralda" to "Santuário esmeralda", "templo_do_sol_poente" to "Templo do sol poente",
    ).map { PersonalArt("wallpapers", it.first, it.second, "png") }
    private val allowed = (banners + wallpapers).associateBy { it.path }

    fun available(banner: Boolean): List<PersonalArt> = if (banner) banners else wallpapers

    /** All options ship in the APK; there is no external import or network path. */
    fun open(context: Context, art: PersonalArt): InputStream {
        require(allowed[art.path] == art)
        return context.assets.open(art.path)
    }

    fun model(art: PersonalArt): String {
        require(allowed[art.path] == art)
        return "file:///android_asset/" + art.path
    }
}
