package ru.begunok.mental

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Questionnaire wording follows the published Russian forms linked by sourceUrl.
// Option positions are the raw answer values saved in history, not display order in the source PDF.
enum class MentalTest(
    val title: String,
    val subtitle: String,
    val period: String,
    val intervalDays: Long,
    val maxScore: Int,
    val questions: List<String>,
    val options: List<String>,
    val sourceUrl: String,
) {
    WHO5(
        title = "WHO-5",
        subtitle = "Общее самочувствие",
        period = "Последние две недели",
        intervalDays = 14,
        maxScore = 100,
        questions = listOf(
            "Я чувствую себя бодрой(-ым) и в хорошем настроении",
            "Я чувствую себя спокойной(-ым) и раскованной(-ым).",
            "Я чувствую себя активной(-ым) и энергичной(-ым).",
            "Я просыпаюсь и чувствую себя свежей(-им) и отдохнувшей(-им).",
            "Каждый день со мной происходят вещи, представляющие для меня интерес.",
        ),
        options = listOf(
            "Никогда", "Некоторое время", "Менее половины времени",
            "Более половины времени", "Большую часть времени", "Все время",
        ),
        sourceUrl = "https://cdn.who.int/media/docs/default-source/mental-health/who-5_russian.pdf",
    ),
    PSS10(
        title = "PSS-10",
        subtitle = "Воспринимаемый стресс",
        period = "За последний месяц",
        intervalDays = 30,
        maxScore = 40,
        questions = listOf(
            "Как часто за последний месяц вы испытывали беспокойство из-за непредвиденных событий?",
            "Как часто за последний месяц Вам казалось сложным контролировать важные события Вашей жизни?",
            "Как часто за последний месяц Вы испытывали нервное напряжение или стресс?",
            "Как часто за последний месяц Вы чувствовали уверенность в том, что справитесь с решением ваших личных проблем?",
            "Как часто за последний месяц Вы чувствовали, что все идет так, как Вы этого хотели?",
            "Как часто за последний месяц Вы думали, что не можете справиться с тем, что вам нужно сделать?",
            "Как часто за последний месяц Вы были в состоянии справиться с вашей раздражительностью?",
            "Как часто за последний месяц Вы чувствовали, что владеете ситуацией?",
            "Как часто за последний месяц Вы чувствовали раздражение из-за того, что происходящие события выходили из-под вашего контроля?",
            "Как часто за последний месяц вам казалось, что накопившиеся трудности достигли такого предела, что Вы не могли их контролировать?",
        ),
        options = listOf("Никогда", "Почти никогда", "Иногда", "Довольно часто", "Часто"),
        sourceUrl = "https://psyjournals.ru/journals/vspu_psychology/archive/2016_n2/vspu_psychology_2016_n2_Ababkov_et_al.pdf",
    ),
    GAD7(
        title = "GAD-7",
        subtitle = "Симптомы тревоги",
        period = "Как часто Вас за последние 14 дней беспокоили следующие проблемы?",
        intervalDays = 14,
        maxScore = 21,
        questions = listOf(
            "Вы нервничали, тревожились или испытывали сильный стресс",
            "Вы были неспособны успокоиться или контролировать свое волнение",
            "Вы слишком сильно волновались по различным поводам",
            "Вам было трудно расслабиться",
            "Вы были настолько суетливы, что Вам было тяжело усидеть на месте",
            "Вы легко злились или раздражались",
            "Вы испытывали страх, словно должно произойти нечто ужасное",
        ),
        options = listOf("Ни разу", "Несколько дней", "Более недели", "Почти каждый день"),
        sourceUrl = "https://www.phqscreeners.com/images/sites/g/files/g10060481/f/201412/GAD7_Russian%20for%20Russia.pdf",
    ),
    PHQ9(
        title = "PHQ-9",
        subtitle = "Симптомы сниженного настроения",
        period = "Как часто за последние 2 недели Вас беспокоили следующие проблемы?",
        intervalDays = 14,
        maxScore = 27,
        questions = listOf(
            "Вам не хотелось ничего делать",
            "У Вас было плохое настроение, Вы были подавлены или испытывали чувство безысходности",
            "Вам было трудно заснуть, у Вас был прерывистый сон, или Вы слишком много спали",
            "Вы были утомлены, или у Вас было мало сил",
            "У Вас был плохой аппетит, или Вы переедали",
            "Вы плохо о себе думали: считали себя неудачником (неудачницей), или были в себе разочарованы, или считали, что подвели свою семью",
            "Вам было трудно сосредоточиться (например, на чтении газеты или на просмотре телепередач)",
            "Вы двигались или говорили настолько медленно, что окружающие это замечали? Или, наоборот, Вы были настолько суетливы или взбудоражены, что двигались гораздо больше обычного",
            "Вас посещали мысли о том, что Вам лучше было бы умереть, или о том, чтобы причинить себе какой-нибудь вред",
        ),
        options = listOf("Ни разу", "Несколько дней", "Более недели", "Почти каждый день"),
        sourceUrl = "https://ifightdepression.com/media/193/download/PHQ-9.pdf?v=1",
    );

    fun score(answers: List<Int>): Int {
        require(answers.size == questions.size) { "Expected ${questions.size} answers for $name" }
        require(answers.all { it in options.indices }) { "Answer outside the range for $name" }
        return when (this) {
            WHO5 -> answers.sum() * 4
            // Russian wording: Ababkov et al. (2016), Appendix 1. Use the original 0..4 scoring,
            // not the adaptation's 1..5: https://www.cmu.edu/common-cold-project/measures-by-study/psychological-and-social-constructs/stress-measures/perceived-stress.html
            PSS10 -> answers.indices.sumOf { index ->
                val answer = answers[index]
                if (index == 3 || index == 4 || index == 6 || index == 7) 4 - answer else answer
            }
            GAD7, PHQ9 -> answers.sum()
        }
    }

    fun interpretation(score: Int): String {
        require(score in 0..maxScore) { "Score outside the range for $name" }
        return when (this) {
            WHO5 -> if (score < 50) {
                "Самочувствие снижено. Более высокий балл означает лучшее самочувствие."
            } else {
                "Более высокий балл означает лучшее самочувствие."
            }
            PSS10 -> "Воспринимаемый стресс: $score из 40. Чем выше балл, тем больше ощущение стресса. Сравнивайте со своими прошлыми результатами."
            GAD7 -> {
                val level = when (score) {
                    in 0..4 -> "Минимальная выраженность симптомов тревоги"
                    in 5..9 -> "Лёгкая выраженность симптомов тревоги"
                    in 10..14 -> "Умеренная выраженность симптомов тревоги"
                    else -> "Высокая выраженность симптомов тревоги"
                }
                level
            }
            PHQ9 -> {
                val level = when (score) {
                    in 0..4 -> "Минимальная выраженность депрессивных симптомов"
                    in 5..9 -> "Лёгкая выраженность депрессивных симптомов"
                    in 10..14 -> "Умеренная выраженность депрессивных симптомов"
                    in 15..19 -> "Выраженные депрессивные симптомы"
                    else -> "Высокая выраженность депрессивных симптомов"
                }
                level
            }
        }
    }

    fun nextDue(completedAt: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(completedAt).atZone(zone).toLocalDate().plusDays(intervalDays)
}

data class MentalResult(
    val id: Long,
    val test: MentalTest,
    val completedAt: Long,
    val answers: List<Int>,
) {
    val score: Int get() = test.score(answers)

    // Any positive response to item 9 matters, even when the total score is low.
    val hasSelfHarmThoughts: Boolean get() = test == MentalTest.PHQ9 && answers.getOrNull(8)?.let { it > 0 } == true
}
