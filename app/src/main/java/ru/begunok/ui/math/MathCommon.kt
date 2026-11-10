package ru.begunok.ui.math

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.LocalDate

internal fun Modifier.mathCompletionDescription(title: String): Modifier = semantics {
    contentDescription = "Урок «$title»: пройден"
}

@Composable
internal fun rememberMathToday(): State<LocalDate> = produceState(LocalDate.now()) {
    while (isActive) {
        value = LocalDate.now()
        delay(60_000)
    }
}

