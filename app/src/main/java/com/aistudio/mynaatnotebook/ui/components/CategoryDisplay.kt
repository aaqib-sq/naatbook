package com.aistudio.mynaatnotebook.ui.components

import androidx.annotation.StringRes
import com.aistudio.mynaatnotebook.R
import com.aistudio.mynaatnotebook.data.NaatCategories

/**
 * Display-only localization of the fixed category taxonomy.
 *
 * Storage, backups, search matching and [NaatCategories.normalize] keep using the
 * canonical English values byte-identical; only the on-screen nameplate is localized.
 * Input is routed through [NaatCategories.normalize] first, so legacy and unknown
 * stored values display as their normalized bucket.
 *
 * Pure function (stored string -> resource id) so it is JVM-unit-testable;
 * Compose callers resolve it with [androidx.compose.ui.res.stringResource].
 */
object CategoryDisplay {

    @StringRes
    fun displayNameRes(stored: String): Int = when (NaatCategories.normalize(stored)) {
        NaatCategories.NAAT -> R.string.category_name_naat
        NaatCategories.HAMD -> R.string.category_name_hamd
        NaatCategories.MANQABAT -> R.string.category_name_manqabat
        NaatCategories.SALAM -> R.string.category_name_salam
        NaatCategories.QASIDA -> R.string.category_name_qasida
        NaatCategories.NASHEED -> R.string.category_name_nasheed
        NaatCategories.MY_KALAM -> R.string.category_name_my_kalam
        else -> R.string.category_name_others
    }
}
