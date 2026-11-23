package ru.begunok.workout

const val STRETCHING_KEY = "stretching"

enum class DayType(val label: String, val storageKey: String) {
    Run("Беговой день", "Run"),
    Strength("Силовой день", "Strength"),
    StrengthUpper("Силовая · Верх тела", "strength_upper"),
    StrengthLower("Силовая · Низ тела", "strength_lower");

    val isSplit: Boolean get() = this == StrengthUpper || this == StrengthLower

    companion object {
        val selectable = listOf(Run, StrengthUpper, StrengthLower)
        fun fromStorage(value: String?): DayType? = entries.firstOrNull { it.storageKey == value }
    }
}

data class Exercise(
    val id: String,
    val name: String,
    val hint: String,
    val startTarget: Int,
    val maxTarget: Int,
    val timed: Boolean = false,
    val perSide: Boolean = false,
    val variationId: String = id,
    val fixedRange: Boolean = false,
) {
    val step: Int get() = if (timed) TIMED_STEP_SEC else 1

    fun amount(value: Int): String = if (timed) "$value с" else if (perSide) "$value на сторону" else "$value"

    fun short(value: Int): String = if (timed) "$value с" else "$value"

    val sideNote: String? get() = if (perSide) "на каждую сторону" else null

    companion object {
        const val TIMED_STEP_SEC = 5
    }
}

data class Ladder(val id: String, val name: String, val variations: List<Exercise>)

object ExerciseCatalog {
    val ladders = listOf(
        Ladder(
            "pull", "Подтягивания",
            listOf(
                Exercise("hang", "Вис на турнике", "Плечи опущены, лопатки собраны", 20, 60, timed = true),
                Exercise("australian", "Австралийские подтягивания", "Лёжа под жердью брусьев, тело прямое, тянуться грудью", 8, 15),
                Exercise("negative_pullups", "Негативные подтягивания", "Запрыгнуть наверх и опускаться 3–5 секунд", 3, 8),
                Exercise("pullups", "Подтягивания", "Подбородок выше перекладины, внизу руки прямые", 5, 12),
                Exercise("wide_pullups", "Подтягивания широким хватом", "Хват шире плеч", 5, 12),
                Exercise("archer_pullups", "Подтягивания лучника", "Подтягиваться к одной руке, вторая прямая", 3, 8, perSide = true),
            ),
        ),
        Ladder(
            "dips", "Брусья",
            listOf(
                Exercise("dip_hold", "Упор на брусьях", "Руки прямые, плечи вниз", 15, 60, timed = true),
                Exercise("negative_dips", "Негативные отжимания на брусьях", "Опускаться 3–5 секунд, подниматься прыжком", 3, 8),
                Exercise("dips", "Отжимания на брусьях", "Опускаться до угла 90° в локтях", 6, 15),
                Exercise("pause_dips", "Отжимания на брусьях с паузой", "Пауза 2 секунды внизу", 5, 12),
            ),
        ),
        Ladder(
            "push", "Отжимания",
            listOf(
                Exercise("incline_pushups", "Отжимания от брусьев", "Руки на жерди брусьев, тело прямое", 10, 20),
                Exercise("pushups", "Отжимания от пола", "Тело прямое, грудь почти касается земли", 10, 20),
                Exercise("diamond_pushups", "Узкие отжимания", "Ладони вместе, локти вдоль тела", 8, 15),
                Exercise("clap_pushups", "Отжимания с хлопком", "Мощно оттолкнуться и хлопнуть", 5, 12),
            ),
        ),
        Ladder(
            "legs", "Ноги",
            listOf(
                Exercise("squats", "Приседания", "Бёдра до параллели, пятки на земле", 15, 30),
                Exercise("lunges", "Выпады назад", "Колено почти касается земли", 8, 15, perSide = true),
                Exercise("jump_squats", "Приседания с выпрыгиванием", "Мягкое приземление", 10, 20),
                Exercise("assisted_pistol", "Пистолетик с опорой", "Держаться за стойку брусьев", 4, 10, perSide = true),
                Exercise("pistol", "Пистолетик", "Приседание на одной ноге", 3, 10, perSide = true),
            ),
        ),
        Ladder(
            "core", "Пресс",
            listOf(
                Exercise("dip_knees", "Подъём коленей в упоре на брусьях", "Без раскачки", 8, 15),
                Exercise("hanging_knees", "Подъём коленей в висе", "Без раскачки, колени к груди", 8, 15),
                Exercise("hanging_legs", "Подъём прямых ног в висе", "Ноги до горизонтали", 5, 12),
                Exercise("l_sit", "Уголок на брусьях", "Прямые ноги параллельно земле", 10, 30, timed = true),
                Exercise("toes_to_bar", "Ноги к перекладине", "Носки касаются турника", 3, 10),
            ),
        ),
        Ladder(
            "plank", "Планка",
            listOf(
                Exercise("plank", "Планка", "Ровная спина, живот напряжён", 30, 90, timed = true),
                Exercise("side_plank", "Боковая планка", "Тело в одну линию", 20, 45, timed = true),
                Exercise("plank_leg", "Планка с подъёмом ноги", "Ноги поднимать по очереди", 30, 60, timed = true),
            ),
        ),
    )

    private val upper = listOf(
        group("Верх · Подтягивания",
            Exercise("upper_pullups", "Подтягивания прямым хватом", "Без раскачки, подбородок выше перекладины", 6, 12),
            Exercise("upper_chinups", "Подтягивания обратным хватом", "Ладони к себе, корпус неподвижен", 6, 12),
            Exercise("upper_assisted_rows", "Австралийские подтягивания", "Облегчённый вариант на низкой перекладине", 6, 12)),
        group("Верх · Брусья", Exercise("upper_dips", "Отжимания на брусьях", "Опускайтесь подконтрольно", 8, 15)),
        group("Верх · Отжимания",
            Exercise("upper_pushups", "Отжимания от земли", "Держите тело в одну линию", 12, 20),
            Exercise("upper_bench_pushups", "Отжимания от лавки", "Поставьте руки на устойчивую лавку", 12, 20)),
        group("Верх · Тяга", Exercise("upper_rows", "Австралийская тяга", "Подтягивайте грудь к низкой перекладине", 10, 15)),
        group("Верх · Трицепс", Exercise("upper_french", "Французские отжимания", "На низком турнике, сгибайте и разгибайте локти", 10, 15)),
        group("Верх · Пресс", Exercise("upper_leg_raises", "Подъём ног в висе", "Поднимайте ноги без раскачки", 10, 15)),
    )

    private val lower = listOf(
        group("Низ · Сплит-присед", Exercise("lower_split_squats", "Болгарские сплит-приседания с опорой", "Используйте низкий турник или брусья как опору", 8, 12, perSide = true)),
        group("Низ · Выпады", Exercise("lower_lunges", "Обратные выпады с места", "Шагните назад, передняя стопа полностью на земле", 10, 12, perSide = true)),
        group("Низ · Присед", Exercise("lower_sumo", "Приседания плие / сумо", "Широкая стойка, пауза 2 секунды внизу", 12, 15)),
        group("Низ · Наклоны", Exercise("lower_good_mornings", "Наклоны «Доброе утро» на двух ногах", "Отводите таз назад, держите спину ровно", 15, 20)),
        group("Низ · Прыжки", Exercise("lower_jumps", "Приседания с выпрыгиванием", "Мягко приземляйтесь на обе ноги", 8, 12)),
        group("Низ · Икры", Exercise("lower_calves", "Подъёмы на носок одной ноги", "Выполняйте с земли, держась за опору", 15, 20, perSide = true)),
    )

    private fun group(name: String, vararg choices: Exercise) =
        Ladder(choices.first().id, name, choices.map { it.copy(fixedRange = true) })

    fun groups(type: DayType): List<Ladder> = when (type) {
        DayType.StrengthUpper -> upper
        DayType.StrengthLower -> lower
        else -> ladders
    }

    private val all = ladders + upper + lower

    fun ladder(id: String): Ladder? = all.firstOrNull { it.id == id }

    fun ladderOf(variationId: String): Ladder? = all.firstOrNull { l -> l.variations.any { it.id == variationId } }

    fun variation(id: String): Exercise? = all.firstNotNullOfOrNull { l -> l.variations.firstOrNull { it.id == id } }

    fun next(variationId: String): Exercise? {
        val ladder = ladderOf(variationId) ?: return null
        val i = ladder.variations.indexOfFirst { it.id == variationId }
        return ladder.variations.getOrNull(i + 1)
    }

    fun level(variationId: String): Pair<Int, Int>? {
        val ladder = ladderOf(variationId) ?: return null
        return ladder.variations.indexOfFirst { it.id == variationId } + 1 to ladder.variations.size
    }
}

data class Slot(val ladderId: String, val variationId: String)

data class DayConfig(
    val slots: List<Slot>,
    val sets: Int,
    val restBetweenSetsSec: Int,
    val restBetweenExercisesSec: Int,
    val lowerCircuit: Boolean = true,
) {
    companion object {
        fun default(type: DayType): DayConfig = when (type) {
            DayType.Run -> DayConfig(
                slots = listOf(Slot("pull", "pullups"), Slot("dips", "dips"), Slot("push", "pushups"), Slot("plank", "plank")),
                sets = 2,
                restBetweenSetsSec = 0,
                restBetweenExercisesSec = 90,
            )
            DayType.Strength -> DayConfig(
                slots = listOf(
                    Slot("pull", "pullups"),
                    Slot("dips", "dips"),
                    Slot("legs", "lunges"),
                    Slot("push", "pushups"),
                    Slot("core", "hanging_knees"),
                ),
                sets = 3,
                restBetweenSetsSec = 90,
                restBetweenExercisesSec = 90,
            )
            DayType.StrengthUpper, DayType.StrengthLower -> DayConfig(
                slots = ExerciseCatalog.groups(type).map { Slot(it.id, it.variations.first().id) },
                sets = 3,
                restBetweenSetsSec = if (type == DayType.StrengthLower) 45 else 30,
                restBetweenExercisesSec = 120,
            )
        }
    }
}

data class StrengthBlock(
    val title: String,
    val exercises: List<Exercise>,
    val sets: Int,
    val circuit: Boolean,
    val restBetweenSetsSec: Int,
    val restBetweenExercisesSec: Int,
) {
    val description: String
        get() = if (circuit) {
            "$title × $sets" +
                (if (restBetweenSetsSec > 0) ", между упражнениями ${formatSpan(restBetweenSetsSec)}" else "") +
                (if (restBetweenExercisesSec > 0) ", между кругами отдых ${formatSpan(restBetweenExercisesSec)}" else "")
        } else {
            "$sets ${plural(sets, "подход", "подхода", "подходов")}, отдых ${formatSpan(restBetweenSetsSec)} между подходами, " +
                "${formatSpan(restBetweenExercisesSec)} между упражнениями"
        }
}

data class DayPlan(
    val type: DayType,
    val warmupSec: Int,
    val strength: StrengthBlock,
) {
    val initialStage: Stage get() = if (type == DayType.Run) Stage.Run else Stage.Strength

    fun runProgram(selected: IntervalProgram?): IntervalProgram? =
        if (initialStage == Stage.Run) selected?.copy(warmupSec = warmupSec) else null

    companion object {
        fun key(type: DayType, variationId: String) = "${type.name}:$variationId"

        fun of(type: DayType, config: DayConfig = DayConfig.default(type)): DayPlan {
            val exercises = config.slots.mapNotNull { slot ->
                ExerciseCatalog.variation(slot.variationId)?.copy(id = key(type, slot.variationId), variationId = slot.variationId)
            }
            return when (type) {
                DayType.Run -> DayPlan(
                    type = type,
                    warmupSec = 5 * 60,
                    strength = StrengthBlock(
                        "Круг на площадке", exercises, config.sets, circuit = true,
                        config.restBetweenSetsSec, config.restBetweenExercisesSec,
                    ),
                )
                DayType.Strength -> DayPlan(
                    type = type,
                    warmupSec = 0,
                    strength = StrengthBlock(
                        "Силовая", exercises, config.sets, circuit = false,
                        config.restBetweenSetsSec, config.restBetweenExercisesSec,
                    ),
                )
                DayType.StrengthUpper, DayType.StrengthLower -> DayPlan(
                    type = type,
                    warmupSec = 0,
                    strength = StrengthBlock(
                        "Круг", exercises, config.sets, circuit = type == DayType.StrengthUpper || config.lowerCircuit,
                        config.restBetweenSetsSec,
                        if (type == DayType.StrengthLower && !config.lowerCircuit) config.restBetweenSetsSec else config.restBetweenExercisesSec,
                    ),
                )
            }
        }
    }
}

data class DoneSet(val exerciseId: String, val reps: Int, val target: Int)

data class LevelUp(val from: Exercise, val to: Exercise)

object Progression {
    fun nextTarget(exercise: Exercise, lastSession: List<DoneSet>, plannedSets: Int = 0): Int {
        if (lastSession.isEmpty()) return exercise.startTarget
        val target = lastSession.maxOf { it.target }
        val minDone = lastSession.minOf { it.reps }
        val maxDone = lastSession.maxOf { it.reps }
        val next = if (minDone >= target && lastSession.size >= plannedSets) {
            maxOf(target + exercise.step, minDone)
        } else {
            maxOf(exercise.step, minOf(target, maxDone))
        }
        return if (exercise.fixedRange) next.coerceIn(exercise.startTarget, exercise.maxTarget) else next
    }

    fun targets(block: StrengthBlock, lastSessions: Map<String, List<DoneSet>>): Map<String, Int> =
        block.exercises.associate { it.id to nextTarget(it, lastSessions[it.id].orEmpty(), block.sets) }

    fun levelUps(block: StrengthBlock, done: List<DoneSet>): List<LevelUp> = block.exercises.mapNotNull { exercise ->
        val sets = done.filter { it.exerciseId == exercise.id }
        if (sets.isEmpty() || nextTarget(exercise, sets, block.sets) <= exercise.maxTarget) return@mapNotNull null
        ExerciseCatalog.next(exercise.variationId)?.let { LevelUp(exercise, it) }
    }

    fun successStreak(recentPrograms: List<Pair<String?, Boolean>>, current: String): Int =
        recentPrograms.takeWhile { (name, completed) -> name == current && completed }.size

    fun shouldSuggestNextLevel(recentPrograms: List<Pair<String?, Boolean>>, current: String): Boolean =
        successStreak(recentPrograms, current) >= LEVEL_UP_SESSIONS

    const val LEVEL_UP_SESSIONS = 3
}

fun plural(n: Int, one: String, few: String, many: String): String = when {
    n % 10 == 1 && n % 100 != 11 -> one
    n % 10 in 2..4 && n % 100 !in 12..14 -> few
    else -> many
}
