package ru.begunok.ui.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.begunok.ui.theme.BegunokTheme

class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BegunokTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Данные о здоровье", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "MyLife читает из Health Connect шаги, пульс, насыщение крови кислородом, сон, " +
                                "сожжённые калории и тренировки, которые туда записывают другие приложения, " +
                                "например Mi Fitness с браслета.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "Данные используются только для показа на экранах приложения: сводка за день " +
                                "и шаги, каденс и калории для ваших тренировок. MyLife ничего не записывает " +
                                "в Health Connect, не отправляет данные в интернет и не передаёт их третьим лицам.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "Отозвать доступ можно в любой момент в настройках Health Connect.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = ::finish) { Text("Понятно") }
                    }
                }
            }
        }
    }
}
