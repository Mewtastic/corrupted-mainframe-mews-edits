package com.github.corruptedinc.corruptedmainframe.discord

import com.github.corruptedinc.corruptedmainframe.Config
import com.github.corruptedinc.corruptedmainframe.audio.Audio
import com.github.corruptedinc.corruptedmainframe.commands.Commands
import com.github.corruptedinc.corruptedmainframe.commands.Commands.Companion.embed
import com.github.corruptedinc.corruptedmainframe.commands.Leveling
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.plugin.Plugin
import dev.minn.jda.ktx.injectKTX
import dev.minn.jda.ktx.listener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.events.ReadyEvent
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.interaction.ButtonClickEvent
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.impl.Log4jLoggerFactory
import java.time.Instant
import java.util.*
import kotlin.concurrent.schedule


@OptIn(ExperimentalCoroutinesApi::class)
class Bot(val config: Config) {
    val log = Log4jLoggerFactory().getLogger("aaaaaaa")
    val startTime: Instant = Instant.now()
    val jda = JDABuilder.create(config.token,
        GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MESSAGE_REACTIONS,
        GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.DIRECT_MESSAGES)
        .disableCache(CacheFlag.ACTIVITY, CacheFlag.EMOTE, CacheFlag.CLIENT_STATUS, CacheFlag.ONLINE_STATUS)
        .injectKTX()
        .build()
    val scope = CoroutineScope(Dispatchers.Default)
    val database = ExposedDatabase(Database.connect(config.databaseUrl, driver = config.databaseDriver).apply {
        useNestedTransactions = true
    })
    val audio = Audio(this)
    val leveling = Leveling(this)
    val buttonListeners = mutableListOf<(ButtonClickEvent) -> Unit>()
    val plugins = mutableListOf<Plugin>()

    companion object {
        /** Number of milliseconds between checking for expiring reminders and mutes. */
        private const val REMINDER_MUTE_RESOLUTION = 1000L
//        private const val PLUGIN_MAIN_CLASS_NAME = "com.plugin.Plugin"
    }

    init {
//        // Coroutines debug
//        DebugProbes.enableCreationStackTraces = false
//        DebugProbes.install()

        Runtime.getRuntime().addShutdownHook(Thread {
            log.info("Saving audio state to database...")
            audio.gracefulShutdown()
            log.info("Finished, exiting")
        })

        // Disabled for the moment, it was causing issues
//        val pluginsDir = File("plugins")
//        // Create plugin dir if it doesn't exist
//        pluginsDir.mkdir()
//        val pluginFiles = pluginsDir.listFiles()
//        for (plugin in pluginFiles ?: emptyArray()) {
//            if (!plugin.name.endsWith(".jar")) continue
//            @Suppress("TooGenericExceptionCaught")  // Anything can happen when loading a plugin
//            try {
//                val jar = JarFile(plugin)
//                val e = jar.entries()
//
//                val urls = arrayOf(URL("jar:file:" + plugin.path + "!/"))
//                val cl = URLClassLoader.newInstance(urls)
//
//                while (e.hasMoreElements()) {
//                    val je: JarEntry = e.nextElement()
//                    if (je.isDirectory || !je.name.endsWith(".class")) {
//                        continue
//                    }
//                    // -6 because of .class
//                    var className: String = je.name.removeSuffix(".class")
//                    className = className.replace('/', '.')
//                    val c: Class<*> = cl.loadClass(className)
//
//                    if (className == PLUGIN_MAIN_CLASS_NAME) {
//                        val loaded = c.constructors.first().newInstance(this) as Plugin
//                        plugins.add(loaded)
//                        loaded.load()
//                    }
//                }
//            } catch (e: Exception) {
//                log.error("Error loading plugin '${plugin.nameWithoutExtension}'!")
//                log.error(e.stackTraceToString())
//            }
//        }


        jda.listener<ReadyEvent> { event ->
                log.info("Logged in as ${event.jda.selfUser.asTag}")
                plugins.forEach { it.botStarted() }

                Timer().schedule(0, REMINDER_MUTE_RESOLUTION) {
                    for (mute in database.moderationDB.expiringMutes()) {
                        try {
                            transaction(database.db) {
                                val guild = jda.getGuildById(mute.guild.discordId)!!
                                val member = guild.getMemberById(mute.user.discordId)!!
                                val roles = database.moderationDB.roleIds(mute).map { guild.getRoleById(it) }
                                guild.modifyMemberRoles(member, roles).queue({}, {})  // ignore errors
                            }
                        } finally {
                            database.moderationDB.removeMute(mute)
                        }
                    }

                    database.trnsctn {
                        for (reminder in database.expiringRemindersNoTransaction()) {
                            try {
                                // Make copies of relevant things for thread safety
                                val text = reminder.text
                                val channel = jda.getTextChannelById(reminder.channelId)
                                jda.retrieveUserById(reminder.user.discordId).queue({ user ->
                                    channel?.sendMessage(user.asMention)?.queue({}, {})  // ignore failure
                                    channel?.sendMessageEmbeds(embed("Reminder", description = text))?.queue({}, {})
                                }, {})
                            } finally {
                                reminder.delete()
                            }
                        }
                    }
                }
            }

        @Suppress("ReturnCount")
        jda.listener<MessageReactionAddEvent> { event ->
            if (event.user?.let { database.banned(it) } == true) return@listener
            val roleId = database.moderationDB.autoRole(event.messageIdLong, event.reactionEmote) ?: return@listener
            val role = event.guild.getRoleById(roleId) ?: return@listener

            // If they're muted they aren't eligible for reaction roles
            val end = event.user?.let { database.moderationDB.findMute(it, event.guild)?.end }
            if (end?.let { Instant.ofEpochSecond(it).isAfter(Instant.now()) } == true) {
                event.reaction.removeReaction(event.user!!).queue()
                return@listener
            }

            event.guild.addRoleToMember(event.userId, role).queue()
        }

        @Suppress("ReturnCount")  // todo maybe fix?  not sure how to make this work
        jda.listener<MessageReactionRemoveEvent> { event ->
            if (event.user?.let { database.banned(it) } == true) return@listener
            val roleId = database.moderationDB.autoRole(event.messageIdLong, event.reactionEmote) ?: return@listener
            val role = event.guild.getRoleById(roleId) ?: return@listener
            event.guild.removeRoleFromMember(event.userId, role).queue()
        }

        jda.listener<GuildJoinEvent> { event ->
            event.guild.loadMembers {
                database.addLink(event.guild, it.user)
            }
        }

        jda.listener<GuildLeaveEvent> { event ->
            database.trnsctn { database.guild(event.guild).currentlyIn = false }
        }
    }

    private val commands = Commands(this)
    init {
//        @Suppress("TooGenericExceptionCaught")
//        try {
//            plugins.forEach { it.registerCommands(commands.handler) }
//        } catch (e: Exception) { log.error(e.stackTraceToString()) }

        jda.listener<GuildMessageReceivedEvent> { event ->
            if (database.banned(event.author)) return@listener
            if (event.author == jda.selfUser) return@listener
            scope.launch {
                leveling.addPoints(event.author, Leveling.POINTS_PER_MESSAGE, event.channel)
            }
        }

        jda.listener<ButtonClickEvent> { event ->
            if (database.banned(event.user)) return@listener
            buttonListeners.forEach { it(event) }
        }
    }
}
