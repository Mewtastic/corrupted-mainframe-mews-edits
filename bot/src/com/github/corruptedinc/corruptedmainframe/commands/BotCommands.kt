package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.utils.ephemeral
import dev.minn.jda.ktx.await
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands.slash
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.system.exitProcess

fun registerBotCommands(bot: Bot) {
    bot.commands.register(
        slash("admin", "Makes a user an admin")
        .addOption(OptionType.USER, "user", "The user to make an admin")) { event ->
        // Don't replace with assertAdmin(), this doesn't allow server admins to make changes
        val isAdmin = bot.database.user(event.user).botAdmin ||
                bot.config.permaAdmins.contains(event.user.id)

        if (!isAdmin) throw CommandException("You must be a bot admin to use this command!")

        val user = event.getOption("user")?.asUser ?: throw CommandException("Couldn't find that user!")

        transaction(bot.database.db) { bot.database.user(user).botAdmin = true }

        event.replyEmbeds(Commands.embed("Successfully made @${user.asTag} a global admin")).ephemeral().await()
    }

    bot.commands.register(
        slash("globalban", "Ban a user from using the bot")
        .addOption(OptionType.USER, "user", "The user to ban", true)) { event ->
        if (!bot.database.user(event.user).botAdmin)
            throw CommandException("You must be a bot admin to use this command!")

        val user = event.getOption("user")?.asUser ?: throw CommandException("Couldn't find the user!")

        bot.database.ban(user)

        event.replyEmbeds(Commands.embed("Banned ${user.asMention}")).ephemeral().await()
    }

    bot.commands.register(
        slash("globalunban", "Unban a user from using the bot")
        .addOption(OptionType.USER, "user", "The user to unban", true)) { event ->
        if (!bot.database.user(event.user).botAdmin)
            throw CommandException("You must be a bot admin to use this command!")

        val user = event.getOption("user")?.asUser ?: throw CommandException("Couldn't find the user!")

        bot.database.unban(user)

        event.replyEmbeds(Commands.embed("Unbanned ${user.asMention}")).ephemeral().await()
    }

    bot.commands.register(
        slash("unadmin", "Revokes a user's bot admin status")
        .addOption(OptionType.USER, "user", "The user to make no longer an admin")) { event ->

        // Don't replace with assertAdmin(), this doesn't allow server admins to make changes
        val isAdmin = bot.database.user(event.user).botAdmin ||
                bot.config.permaAdmins.contains(event.user.id)

        if (!isAdmin) throw CommandException("You must be a bot admin to use this command!")

        val user = event.getOption("user")?.asUser ?: throw CommandException("Couldn't find that user!")

        if (bot.config.permaAdmins.contains(user.id)) throw CommandException("That user is a permanent admin!")

        transaction(bot.database.db) { bot.database.user(user).botAdmin = false }

        event.replyEmbeds(Commands.embed("Successfully made @${user.asTag} not a global admin")).ephemeral().await()
    }


    bot.commands.register(slash("restart", "Restart the bot")) { event ->
        // Don't replace with assertAdmin(), this doesn't allow server admins to run
        val isAdmin = bot.database.user(event.user).botAdmin ||
                bot.config.permaAdmins.contains(event.user.id)

        if (!isAdmin) throw CommandException("You need to be admin to use this command!")

//            bot.audio.gracefulShutdown()  // handled by shutdown hook in Bot.kt
        bot.log.error("${event.user.asTag} (id ${event.user.id}) ran /restart")
        event.replyEmbeds(Commands.embed("Shutting down...")).ephemeral().await()
        exitProcess(0)
    }
}
