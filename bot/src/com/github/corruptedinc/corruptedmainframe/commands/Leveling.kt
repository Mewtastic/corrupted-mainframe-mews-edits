package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.commands.Commands.Companion.embed
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.utils.ephemeral
import com.github.corruptedinc.corruptedmainframe.utils.sq
import dev.minn.jda.ktx.await
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.TextChannel
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.Commands.slash
import kotlin.math.*

@Suppress("MagicNumber")
class Leveling(private val bot: Bot) {
    companion object {
        const val POINTS_PER_MESSAGE = 1.0
        private const val LEVEL_BAR_WIDTH = 24

        fun fightPoints(level: Double, zeroToOne: Double) = ((10 * (level + 2).pow(0.25) + 10) * zeroToOne) + 5

        // TODO: move some other methods here as well
    }

    fun pointsToLevel(points: Double) = ln((points + 60) / 10).sq() - 3

    fun levelToPoints(level: Double) = (exp(sqrt(level + 3)) * 10) - 60

    fun starboardPoints(level: Double) = 5 * (level + 2).pow(0.25) + 10

    fun level(user: User, guild: Guild) = pointsToLevel(bot.database.points(user, guild))

    fun points(user: User, guild: Guild) = bot.database.points(user, guild)

    suspend fun addPoints(user: User, points: Double, channel: TextChannel) {
        if (user.isBot) return

        // I'm aware this is bad, but this is run in a spot where there isn't any catching
        @Suppress("TooGenericExceptionCaught")
        try {
            val previousLevel = level(user, channel.guild).toInt()
            bot.database.addPoints(user, channel.guild, points)
            val level = level(user, channel.guild).toInt()
            if (level > previousLevel && bot.database.popups(user, channel.guild)) {
                if (!bot.database.trnsctn { bot.database.guild(channel.guild).levelsEnabled }) return
                channel.sendMessageEmbeds(
                    embed(
                        "Level Up",
                        description = "${user.asMention} has leveled up from $previousLevel to $level!  Only ${
                            (levelToPoints(level + 1.0) - levelToPoints(level.toDouble())).roundToInt()
                        } XP to go to level ${level + 1}!\nTo disable these notifications, run /levelnotifs false, " +
                                "or /togglelevels for a guild-wide switch",
                        stripPings = false,
                    )
                ).await()
            }
        } catch (e: Exception) {
            bot.log.error("Exception while adding points:\n${e.stackTraceToString()}")
        }
    }

    fun registerCommands() {
        bot.commands.register(
            slash("level", "Gets your current level")
            .addOption(OptionType.USER, "user", "The user to get the level of (optional)", false)
        ) { event ->
            val user = event.getOption("user")?.asUser ?: event.user
            val xp = bot.leveling.points(user, event.guild!!)
            val level = bot.leveling.pointsToLevel(xp)
            val levelStartXP = bot.leveling.levelToPoints(floor(level))
            val levelEndXP = bot.leveling.levelToPoints(ceil(level))

            val portion = (xp - levelStartXP) / (levelEndXP - levelStartXP)

            val parts = " ▏▎▍▌▋▊▉█"
            val blocks = LEVEL_BAR_WIDTH * portion
            @Suppress("MagicNumber")
            val out = (parts.last().toString().repeat(blocks.toInt()) +
                    parts[((blocks - blocks.toInt().toDouble()) * 8).toInt()]).padEnd(LEVEL_BAR_WIDTH, ' ')

            val start = levelStartXP.toInt().coerceAtLeast(0).toString()
            val end = levelEndXP.toInt().toString()

            event.replyEmbeds(embed("Level ${level.toInt()}", description = "${user.asMention} has " +
                    "${xp.toInt()} points\nonly ${(levelEndXP - xp).roundToInt()} points to " +
                    "go until level ${level.toInt() + 1}!\n" +
                    "`" + start.padEnd(LEVEL_BAR_WIDTH + 2 - end.length, ' ') + end + "`\n" +
                    "`|$out|`",
                thumbnail = user.effectiveAvatarUrl,
                stripPings = false)).await()
        }

        bot.commands.register(
            slash("togglelevels", "Enable or disable level notifications for the whole server.")
            .addOption(OptionType.BOOLEAN, "enabled", "If level up notifications should be shown.", true)
        ) { event ->
            bot.commands.assertPermissions(event, Permission.ADMINISTRATOR)
            val e = event.getOption("enabled")!!.asBoolean
            bot.database.trnsctn {
                bot.database.guild(event.guild!!).levelsEnabled = e
            }
            event.replyEmbeds(embed("Successfully ${if (e) "enabled" else "disabled"} level notifications")).await()
        }

        bot.commands.register(slash("levelnotifs", "Toggle level notifications")
            .addOption(OptionType.BOOLEAN, "enabled", "If you should be shown level notifications")) { event ->
            val enabled = event.getOption("enabled")!!.asBoolean
            bot.database.setPopups(event.user, event.guild ?:
            throw CommandException("This command must be run in a server!"), enabled)
            event.replyEmbeds(embed("Set level popups to $enabled")).await()
        }

        bot.commands.registerUser(Commands.user("level")) { event ->
            val user = event.targetMember!!.user
            val xp = bot.leveling.points(user, event.guild!!)
            val level = bot.leveling.pointsToLevel(xp)
            val levelStartXP = bot.leveling.levelToPoints(floor(level))
            val levelEndXP = bot.leveling.levelToPoints(ceil(level))

            val portion = (xp - levelStartXP) / (levelEndXP - levelStartXP)

            val parts = " ▏▎▍▌▋▊▉█"
            val blocks = LEVEL_BAR_WIDTH * portion
            @Suppress("MagicNumber")
            val out = (parts.last().toString().repeat(blocks.toInt()) +
                    parts[((blocks - blocks.toInt().toDouble()) * 8).toInt()]).padEnd(LEVEL_BAR_WIDTH, ' ')

            val start = levelStartXP.toInt().coerceAtLeast(0).toString()
            val end = levelEndXP.toInt().toString()

            event.replyEmbeds(embed("Level ${level.toInt()}", description = "${user.asMention} has " +
                    "${xp.toInt()} points\nonly ${(levelEndXP - xp).roundToInt()} points to " +
                    "go until level ${level.toInt() + 1}!\n" +
                    "`" + start.padEnd(LEVEL_BAR_WIDTH + 2 - end.length, ' ') + end + "`\n" +
                    "`|$out|`",
                thumbnail = user.effectiveAvatarUrl,
                stripPings = false)).ephemeral().await()
        }
    }
}
