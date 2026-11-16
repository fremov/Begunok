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
                            "MyLife читает из Health Connect шаги, пульс, насыщение крови кислородом, сон и переданные фазы сна, " +
                                "активные калории, расстояние и шаговый каденс, которые туда записывают " +
                                "другие приложения, например Gadgetbridge или Mi Fitness с браслета. " +
                                "Импортированные тренировки, скорость, набор высоты и велокаденс не читаются.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "Данные используются для сводки за день, истории и показателей ваших тренировок. " +
                                "Для автоматического обновления MyLife читает разрешённые показатели в фоне " +
                                "и хранит дневные показатели и измерения пульса и кислорода с их исходным временем " +
                                "в отдельной локальной базе в закрытом хранилище приложения. " +
                                "При первом чтении запрашиваются последние 30 дней; полученная история " +
                                "сохраняется без ограничения срока, в том числе после отзыва разрешений. " +
                                "Старые измерения не становятся новыми при обновлении данных. " +
                                "MyLife ничего не записывает в Health Connect, не отправляет показатели в интернет " +
                                "и не передаёт их третьим лицам. Экспорт для компьютера создаётся отдельно " +
                                "только по явному запросу через USB.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "По отдельно выбранной вами папке MyLife также читает локальный SQLite-экспорт " +
                                "Gadgetbridge: стресс и показатель активности Xiaomi Vitality Score. " +
                                "Эти показатели сохраняются со временем источника в закрытой базе MyLife. " +
                                "Настроение и субъективная энергия берутся только из ваших собственных отметок, " +
                                "а не вычисляются по стрессу или пульсу. Локальный файл экспорта содержит " +
                                "данные о здоровье; не публикуйте его и не передавайте посторонним приложениям.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "Разрешения на чтение показателей, истории и фоновое чтение можно отозвать " +
                                "в любой момент в настройках Health Connect. Это останавливает новые чтения, " +
                                "но не удаляет уже сохранённую локальную историю.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = ::finish) { Text("Понятно") }
                    }
                }
            }
        }
    }
}
