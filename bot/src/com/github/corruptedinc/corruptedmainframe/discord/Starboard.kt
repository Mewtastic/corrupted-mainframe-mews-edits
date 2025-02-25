package com.github.corruptedinc.corruptedmainframe.discord

import com.github.corruptedinc.corruptedmainframe.commands.Commands
import com.github.corruptedinc.corruptedmainframe.commands.Commands.Companion.stripPings
import com.github.corruptedinc.corruptedmainframe.commands.Leveling
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import dev.minn.jda.ktx.await
import dev.minn.jda.ktx.listener
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent

class Starboard(private val bot: Bot) {
    init {
        bot.jda.listener<MessageReactionAddEvent> { event ->
            var emote: String? = null
            val thresh = bot.database.trnsctn {
                if (bot.database.banned(event.user ?: return@trnsctn null)) {
                    event.reaction.removeReaction().queue({}, {})  // probably handles errors?
                    return@trnsctn null
                }
                val g = bot.database.guild(event.guild)
                g.starboardChannel ?: return@trnsctn null
                if (event.reactionEmote.name != g.starboardReaction) return@trnsctn null
                if (event.channel.idLong == g.starboardChannel) return@trnsctn null
                if (g.starredMessages.any { it.messageID == event.messageIdLong }) {  // TODO slowish
                    return@trnsctn null
                }
                emote = String(g.starboardReaction!!.toCharArray())  // make a copy just in case
                return@trnsctn g.starboardThreshold
            } ?: return@listener

            val shouldStar = thresh <=
                    event.retrieveMessage().await().reactions.find { it.reactionEmote.name == emote }!!.count

            if (shouldStar) star(event.retrieveMessage().await())
        }
    }

    fun unstarDB(message: Message) {
        bot.database.trnsctn {
            ExposedDatabase.StarredMessage.find { ExposedDatabase.StarredMessages.messageID eq message.idLong }
                .firstOrNull()?.delete()

            // TODO this isn't quite right if the user changes levels between, keep points earned in DB?
            bot.database.addPoints(message.author, message.guild,
                -bot.leveling.starboardPoints(bot.leveling.level(message.author, message.guild)))
        }
    }

    suspend fun star(message: Message) {
        if (bot.database.trnsctn {
                val g = bot.database.guild(message.guild)
                if (g.starredMessages.any { it.messageID == message.idLong }) return@trnsctn true
                ExposedDatabase.StarredMessage.new {
                    this.guild = g.id
                    this.messageID = message.idLong
                }
                return@trnsctn false
        }) return

        bot.leveling.addPoints(message.author,
            bot.leveling.starboardPoints(bot.leveling.level(message.author, message.guild)), message.textChannel)

        val embed = Commands.embed("Starboard", message.jumpUrl,
            description = "${message.member?.asMention}:\n" + message.contentRaw.ifBlank { null }?.stripPings(),
            color = message.member?.color, thumbnail = message.member?.effectiveAvatarUrl,
            imgUrl = message.attachments.firstOrNull { it.isImage || it.isVideo }?.url,
            timestamp = message.timeCreated,
            stripPings = false
        )
        val channel = message.guild.getTextChannelById(bot.database.trnsctn {
            val g = bot.database.guild(message.guild)
            g.starboardChannel
        }!!)!!
        channel.sendMessageEmbeds(embed).await()
    }
}
