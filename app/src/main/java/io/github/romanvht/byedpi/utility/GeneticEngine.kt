package io.github.romanvht.byedpi.utility

import kotlin.random.Random

/**
 * Настройки генетического подбора стратегий.
 *
 * Размер поколения: [parents] * (1 + [additions] + [deletions] + [changes]),
 * то есть для каждого родителя создаются: он сам, [additions] вариантов с
 * добавленным параметром, [deletions] с удалённым и [changes] с изменённым.
 */
data class GeneticSettings(
    val parents: Int = 5,
    val additions: Int = 3,
    val deletions: Int = 1,
    val changes: Int = 2,
    val timeLimitMinutes: Int = 120,
    val stopAtFull: Int = 3,
    val bestCount: Int = 10,
) {
    val generationSize: Int
        get() = parents.coerceAtLeast(1) * (
            1 + additions.coerceAtLeast(0) + deletions.coerceAtLeast(0) + changes.coerceAtLeast(0)
        )
}

/** Описание одного параметра ciadpi, который алгоритм может добавлять, удалять и менять. */
internal class GeneticOption(
    val key: Char,
    val longName: String,
    val values: List<String> = emptyList(),
    val generator: ((Random) -> String)? = null,
    val repeatable: Boolean = false,
) {
    val hasValue: Boolean get() = values.isNotEmpty() || generator != null

    /**
     * Случайное значение параметра, не равное [current] (если это возможно).
     */
    fun randomValue(random: Random, current: String? = null): String {
        repeat(12) {
            val value = generator?.invoke(random) ?: values.random(random)
            if (value != current) return value
        }
        return values.firstOrNull { it != current } ?: generator?.invoke(random) ?: ""
    }
}

/**
 * Каталог параметров byedpi (по README и парсеру main.c).
 *
 * Параметры можно исключить из подбора, добавив их ключ в [excludedKeys].
 */
internal object GeneticOptions {

    /**
     * Ключи, которые не участвуют в мутациях.
     * - D, w — демон и pid-файл (не собираются для Android);
     * - E — прозрачный прокси (не собирается для Android);
     * - y — дамп кэша в файл (на Android не нужен).
     *
     * Чтобы исключить что-то ещё, достаточно добавить ключ сюда.
     */
    private val excludedKeys: Set<Char> = setOf('D', 'w', 'E', 'y')

    /**
     * Параметры, которые непосредственно ломают DPI. Последний из них
     * алгоритм не удаляет, чтобы не получить стратегию без десинхронизации.
     */
    val desyncKeys: Set<Char> = setOf('s', 'd', 'o', 'q', 'f')

    private val positionFlags = listOf(
        "", "+s", "+h", "+n", "+se", "+he", "+ne",
        "+sm", "+hm", "+nm", "+sr", "+hr", "+nr", "+ss", "+hs", "+ns",
    )

    private fun position(random: Random, allowNegative: Boolean = true): String {
        val flag = positionFlags.random(random)
        // +s/+h прибавляют смещение SNI/Host, поэтому большой отрицательный
        // offset может увести позицию за начало пакета
        val relativeToHost = flag.startsWith("+s") || flag.startsWith("+h")
        val value = when {
            !allowNegative -> random.nextInt(0, 31)
            relativeToHost -> random.nextInt(0, 21)
            random.nextInt(8) == 0 -> random.nextInt(0, 31)
            else -> -random.nextInt(1, 200)
        }
        val repeats = if (random.nextInt(5) == 0) ":${random.nextInt(2, 6)}" else ""
        val skip = if (repeats.isNotEmpty() && random.nextInt(3) == 0) ":${random.nextInt(1, 6)}" else ""
        return "$value$repeats$skip$flag"
    }

    private val all: List<GeneticOption> = listOf(
        // общие параметры запуска
        GeneticOption('D', "daemon"),
        GeneticOption('w', "pidfile", values = listOf("/tmp/ciadpi.pid")),
        GeneticOption('E', "transparent"),
        GeneticOption('N', "no-domain"),
        GeneticOption('X', "no-ipv6"),
        GeneticOption('U', "no-udp"),
        GeneticOption('i', "ip", values = listOf("127.0.0.1", "0.0.0.0")),
        GeneticOption('p', "port", values = listOf("1080", "1081", "8080", "3128")),
        GeneticOption('I', "conn-ip", values = listOf("127.0.0.1", "0.0.0.0")),
        GeneticOption('b', "buf-size", values = listOf("1024", "2048", "4096", "8192", "16384", "32768")),
        GeneticOption('c', "max-conn", values = listOf("16", "32", "64", "128", "256", "512")),
        GeneticOption(
            'A', "auto",
            values = listOf("n", "t", "r", "s", "c", "torst", "ts", "rs", "tsc", "trs", "n"),
        ),
        GeneticOption('L', "auto-mode", values = listOf("o", "s", "n", "o,s", "s,n", "o,s,n")),
        GeneticOption('u', "cache-ttl", values = listOf("60", "300", "600", "3600")),
        GeneticOption(
            'T', "timeout",
            values = listOf("1", "2", "5", "10", "5:0.5", "10:2", "3:1:5", "5:1:10:1000"),
        ),
        GeneticOption('K', "proto", values = listOf("t", "h", "u", "t,h", "t,i", "h,i", "t,h,i")),
        GeneticOption(
            'H', "hosts",
            values = listOf(":example.com", ":www.google.com", ":discord.com", ":youtube.com"),
            repeatable = true,
        ),
        GeneticOption(
            'j', "ipset",
            values = listOf(":1.1.1.1/32", ":8.8.8.8/32", ":142.250.0.0/16"),
            repeatable = true,
        ),
        GeneticOption('V', "pf", values = listOf("80", "443", "80-443")),
        GeneticOption('R', "round", values = listOf("1", "2", "3", "1-3", "1-5", "2-7")),
        GeneticOption('F', "tfo"),
        GeneticOption('Z', "wait-send"),

        // десинхронизация
        GeneticOption('s', "split", generator = { position(it) }, repeatable = true),
        GeneticOption('d', "disorder", generator = { position(it) }, repeatable = true),
        GeneticOption('o', "oob", generator = { position(it) }, repeatable = true),
        GeneticOption('q', "disoob", generator = { position(it) }, repeatable = true),
        GeneticOption('f', "fake", generator = { position(it) }, repeatable = true),
        GeneticOption('O', "fake-offset", generator = { position(it) }),
        GeneticOption('r', "tlsrec", generator = { position(it, allowNegative = false) }, repeatable = true),
        GeneticOption('t', "ttl", values = listOf("1", "2", "3", "4", "5", "6", "8", "10", "12", "16", "32", "64")),
        GeneticOption('g', "def-ttl", values = listOf("1", "3", "5", "8", "16", "32", "64")),
        GeneticOption('S', "md5sig"),
        GeneticOption(
            'l', "fake-data",
            values = listOf(
                ":GET / HTTP/1.1\\r\\nHost: example.com\\r\\n\\r\\n",
                ":\\x16\\x03\\x01\\x00\\x00\\x00\\x00\\x00",
            ),
        ),
        GeneticOption('e', "oob-data", values = listOf("a", "b", "x", "\\x01")),
        GeneticOption(
            'n', "fake-sni",
            values = listOf("www.google.com", "google.com", "???.example.com", "*#*.example.com"),
            repeatable = true,
        ),
        GeneticOption(
            'Q', "fake-tls-mod",
            values = listOf("r", "o", "r,m=100", "o,m=100", "r,m=-10", "o,m=-10"),
        ),
        GeneticOption('M', "mod-http", values = listOf("h", "d", "r", "h,d", "h,r", "d,r", "h,d,r")),
        GeneticOption('m', "tlsminor", values = listOf("1", "2", "3", "4")),
        GeneticOption('a', "udp-fake", values = listOf("0", "1", "2", "3", "5", "10")),
        GeneticOption('Y', "drop-sack"),
        GeneticOption('y', "cache-file", values = listOf("-")),
    ).filter { it.key !in excludedKeys }

    private val shortOptions: Map<Char, GeneticOption> = all.associateBy { it.key }
    private val longOptions: Map<String, GeneticOption> = all.associateBy { it.longName }

    val options: List<GeneticOption> get() = all

    fun byKey(key: Char): GeneticOption? = shortOptions[key]

    fun byLongName(name: String): GeneticOption? = longOptions[name]
}

/** Один параметр стратегии. Для неизвестных параметров [key] равен null. */
internal data class GeneticArgument(
    val key: Char?,
    val value: String?,
    val raw: List<String>,
)

/** Разобранная строка стратегии. */
internal data class GeneticStrategy(
    val leading: List<String>,
    val arguments: List<GeneticArgument>,
) {
    fun render(): String = GeneticEngine.render(this)
}

/**
 * Разбор и генерация стратегий byedpi для генетического алгоритма.
 *
 * Работает только со строками и не зависит от Android, поэтому его можно
 * проверять обычными JVM-тестами.
 */
internal object GeneticEngine {

    /**
     * Разбирает строку стратегии на отдельные параметры.
     * Неизвестные параметры сохраняются как есть, чтобы ничего не потерять.
     */
    fun parse(command: String): GeneticStrategy {
        val tokens = shellSplit(command)
        val leading = mutableListOf<String>()
        val arguments = mutableListOf<GeneticArgument>()
        var seenOption = false

        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]

            if (token == "--") {
                while (index < tokens.size) {
                    arguments += GeneticArgument(null, null, listOf(tokens[index]))
                    index++
                }
                break
            }

            if (token.startsWith("--") && token.length > 2) {
                val body = token.substring(2)
                val name = body.substringBefore('=')
                val option = GeneticOptions.byLongName(name)
                seenOption = true

                if (body.contains('=')) {
                    if (option != null && !option.hasValue) {
                        arguments += GeneticArgument(null, null, listOf(token))
                    } else {
                        arguments += GeneticArgument(option?.key, body.substringAfter('='), listOf(token))
                    }
                    index++
                } else if (option?.hasValue == true) {
                    val value = tokens.getOrNull(index + 1)
                    if (value == null) {
                        arguments += GeneticArgument(null, null, listOf(token))
                        index++
                    } else {
                        arguments += GeneticArgument(option.key, value, listOf(token, value))
                        index += 2
                    }
                } else {
                    arguments += GeneticArgument(option?.key, null, listOf(token))
                    index++
                }
                continue
            }

            if (token.length >= 2 && token[0] == '-' && token[1] != '-') {
                val key = token[1]
                val option = GeneticOptions.byKey(key)
                val rest = token.substring(2)
                seenOption = true

                when {
                    option == null || !option.hasValue -> {
                        if (option != null && rest.isNotEmpty()) {
                            // флаги byedpi не склеиваются — сохраняем токен как есть
                            arguments += GeneticArgument(null, null, listOf(token))
                        } else {
                            arguments += GeneticArgument(option?.key, null, listOf(token))
                        }
                        index++
                    }

                    rest.isNotEmpty() -> {
                        arguments += GeneticArgument(key, rest, listOf(token))
                        index++
                    }

                    else -> {
                        val value = tokens.getOrNull(index + 1)
                        if (value == null) {
                            arguments += GeneticArgument(null, null, listOf(token))
                            index++
                        } else {
                            arguments += GeneticArgument(key, value, listOf(token, value))
                            index += 2
                        }
                    }
                }
                continue
            }

            if (seenOption) {
                arguments += GeneticArgument(null, null, listOf(token))
            } else {
                leading += token
            }
            index++
        }

        return GeneticStrategy(leading, arguments)
    }

    /** Собирает строку стратегии обратно из разобранного представления. */
    fun render(strategy: GeneticStrategy): String = buildList {
        strategy.leading.forEach { add(quote(it)) }
        strategy.arguments.forEach { add(renderArgument(it)) }
    }.joinToString(" ").trim()

    /**
     * Создаёт новое поколение из [parents].
     *
     * Для каждого родителя: он сам без изменений, [additions] стратегий с
     * добавленным параметром, [deletions] с удалённым и [changes] с изменённым.
     * Удаляемые параметры у одного родителя всегда разные.
     */
    fun nextGeneration(
        parents: List<String>,
        additions: Int,
        deletions: Int,
        changes: Int,
        random: Random = Random.Default,
    ): List<String> {
        val parsedParents = parents
            .map { parse(it) }
            .filter { it.arguments.isNotEmpty() }
            .distinctBy { it.render() }
        if (parsedParents.isEmpty()) return emptyList()

        val desired = parsedParents.size * (
            1 + additions.coerceAtLeast(0) + deletions.coerceAtLeast(0) + changes.coerceAtLeast(0)
        )
        val result = LinkedHashSet<String>()

        // лучшие стратегии переходят в новое поколение без изменений
        parsedParents.forEach { result += it.render() }

        for (parent in parsedParents) {
            val deletedKeys = mutableSetOf<Char>()
            repeat(deletions.coerceAtLeast(0)) {
                deletionVariant(parent, deletedKeys, random)?.let { result += it.render() }
            }
            repeat(additions.coerceAtLeast(0)) {
                additionVariant(parent, random)?.let { result += it.render() }
            }
            repeat(changes.coerceAtLeast(0)) {
                changeVariant(parent, random)?.let { result += it.render() }
            }
        }

        // добираем поколение, если часть мутаций совпала с уже существующими
        var attempts = 0
        val maxAttempts = desired * 20
        while (result.size < desired && attempts < maxAttempts) {
            attempts++
            val parent = parsedParents.random(random)
            val variant = when (random.nextInt(3)) {
                0 -> additionVariant(parent, random)
                1 -> changeVariant(parent, random)
                else -> deletionVariant(parent, mutableSetOf(), random)
            } ?: continue
            result += variant.render()
        }

        return result.toList()
    }

    private fun deletionVariant(
        parent: GeneticStrategy,
        deletedKeys: MutableSet<Char>,
        random: Random,
    ): GeneticStrategy? {
        if (parent.arguments.size <= 1) return null
        val remaining = parent.arguments.withIndex().filter { (_, argument) ->
            val key = argument.key
            key != null && key !in deletedKeys
        }
        val desyncLeft = remaining.count { (_, argument) ->
            argument.key?.let { it in GeneticOptions.desyncKeys } == true
        }
        val candidates = remaining.filter { (_, argument) ->
            val key = argument.key ?: return@filter false
            !(key in GeneticOptions.desyncKeys && desyncLeft <= 1)
        }.map { it.index }
        if (candidates.isEmpty()) return null

        val index = candidates.random(random)
        val key = parent.arguments[index].key ?: return null
        deletedKeys += key
        return GeneticStrategy(
            leading = parent.leading,
            arguments = parent.arguments.filterIndexed { position, _ -> position != index },
        )
    }

    private fun additionVariant(parent: GeneticStrategy, random: Random): GeneticStrategy? {
        val present = parent.arguments.mapNotNull { it.key }.toSet()
        val candidates = GeneticOptions.options.filter { option ->
            when {
                option.key in present -> option.repeatable
                else -> true
            }
        }
        if (candidates.isEmpty()) return null

        val option = candidates.random(random)
        val value = if (option.hasValue) option.randomValue(random) else null
        val argument = GeneticArgument(option.key, value, emptyList())
        val position = random.nextInt(parent.arguments.size + 1)
        return GeneticStrategy(
            leading = parent.leading,
            arguments = parent.arguments.toMutableList().apply { add(position, argument) },
        )
    }

    private fun changeVariant(parent: GeneticStrategy, random: Random): GeneticStrategy? {
        val valueCandidates = parent.arguments.indices.filter { index ->
            val argument = parent.arguments[index]
            val option = argument.key?.let { GeneticOptions.byKey(it) }
            option?.hasValue == true && argument.value != null
        }
        val flagCandidates = parent.arguments.indices.filter { index ->
            val argument = parent.arguments[index]
            val option = argument.key?.let { GeneticOptions.byKey(it) }
            option != null && !option.hasValue && argument.value == null
        }

        val changeValue = valueCandidates.isNotEmpty() &&
            (flagCandidates.isEmpty() || random.nextBoolean())

        if (changeValue) {
            val index = valueCandidates.random(random)
            val argument = parent.arguments[index]
            val option = argument.key?.let { GeneticOptions.byKey(it) } ?: return null
            val value = option.randomValue(random, argument.value)
            return GeneticStrategy(
                leading = parent.leading,
                arguments = parent.arguments.toMutableList().apply {
                    set(index, GeneticArgument(option.key, value, emptyList()))
                },
            )
        }

        if (flagCandidates.isEmpty()) return null
        val present = parent.arguments.mapNotNull { it.key }.toSet()
        val replacement = GeneticOptions.options
            .filter { !it.hasValue && it.key !in present }
            .randomOrNull(random) ?: return null
        val index = flagCandidates.random(random)
        return GeneticStrategy(
            leading = parent.leading,
            arguments = parent.arguments.toMutableList().apply {
                set(index, GeneticArgument(replacement.key, null, emptyList()))
            },
        )
    }

    private fun renderArgument(argument: GeneticArgument): String {
        val key = argument.key
        if (key == null) return argument.raw.joinToString(" ") { quote(it) }

        val option = GeneticOptions.byKey(key)
        val value = argument.value
            ?: return "-$key"

        if (option == null) return argument.raw.joinToString(" ") { quote(it) }

        return if (value.isNotEmpty() && !needsQuotes(value)) {
            "-$key$value"
        } else if (value.isEmpty()) {
            "-$key"
        } else {
            "--${option.longName} ${quote(value)}"
        }
    }

    private fun needsQuotes(value: String): Boolean =
        value.any { it.isWhitespace() || it == '"' || it == '\'' }

    private fun quote(value: String): String =
        if (needsQuotes(value)) "\"" + value.replace("\"", "\\\"") + "\"" else value
}
