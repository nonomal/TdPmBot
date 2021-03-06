package io.github.nekohasekai.pm

import cn.hutool.core.date.DateUtil
import cn.hutool.core.date.SystemClock
import cn.hutool.core.io.FileUtil
import cn.hutool.log.level.Level
import io.github.nekohasekai.nekolib.cli.TdCli
import io.github.nekohasekai.nekolib.cli.TdLoader
import io.github.nekohasekai.nekolib.core.raw.getChatWith
import io.github.nekohasekai.nekolib.core.utils.*
import io.github.nekohasekai.nekolib.i18n.*
import io.github.nekohasekai.nekolib.utils.GetIdCommand
import io.github.nekohasekai.pm.database.*
import io.github.nekohasekai.pm.instance.*
import io.github.nekohasekai.pm.manage.AdminCommands
import io.github.nekohasekai.pm.manage.CreateBot
import io.github.nekohasekai.pm.manage.MyBots
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import td.TdApi
import java.io.File
import java.util.*
import kotlin.concurrent.schedule
import kotlin.system.exitProcess

object Launcher : TdCli(), PmInstance {

    val public get() = booleanEnv("PUBLIC")

    override val admin by lazy { intEnv("ADMIN").toLong() }
    override val L get() = LocaleController.forChat(admin)

    override val integration get() = BotIntegration.Cache.fetch(me.id).value
    override val settings get() = BotSetting.Cache.fetch(me.id).value
    override val blocks by lazy { UserBlocks.Cache(me.id) }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {

        readSettings("pm.conf")?.insertProperties()

        val logLevel = stringEnv("LOG_LEVEL").takeIf { !it.isNullOrBlank() }?.toUpperCase() ?: "INFO"

        runCatching {

            LOG_LEVEL = Level.valueOf(logLevel)

        }.onFailure {

            LOG_LEVEL = Level.INFO

            defaultLog.error("Invalid log level $logLevel, fallback to INFO.")

        }

        TdLoader.tryLoad()

        args.forEachIndexed { index, arg ->

            if (arg == "--download-library") {

                exitProcess(0)

            } else if (arg == "--backup") {

                var backupTo: File

                backupTo = getFile(if (args.size <= index + 1) {

                    "."

                } else {

                    args[index + 1]

                })

                if (backupTo.isDirectory) {

                    backupTo = File(backupTo, "td-pm-backup-${DateUtil.format(Date(), "yyyyMMdd-HHmmss")}.tar.xz")

                } else if (!backupTo.name.endsWith(".tar.xz")) {

                    defaultLog.error(">> File name must ends with .tar.xz")

                    exitProcess(100)

                }

                val output = FileUtil.touch(backupTo).tarXz()

                output.writeFile("pm.conf")

                // 数据目录

                output.writeDirectory("data/")

                // 数据库

                output.writeFile("data/pm_data.db")

                output.writeFile("data/td.binlog")

                val pmBots = getFile("data/pm").listFiles()

                if (!pmBots.isNullOrEmpty()) {

                    output.writeDirectory("data/pm/")

                    pmBots.forEach {

                        output.writeDirectory("data/pm/${it.name}/")

                        output.writeFile("data/pm/${it.name}/td.binlog")

                    }

                }

                output.finish()

                output.close()

                defaultLog.info(">> Saved to ${backupTo.canonicalPath}")

                exitProcess(0)

            }

        }


        if (admin == 0L) {

            defaultLog.warn("Admin not specified, use /id to get your userid.")

        }

        start()


    }

    override fun onLoad() {

        super.onLoad()

        defaultLog.debug("Init databases")

        initDatabase("pm_data.db")

        LocaleController.initWithDatabase(database)

        initPersistDatabase()

        database.write {

            SchemaUtils.createMissingTablesAndColumns(
                    UserBots,
                    StartMessages,
                    ActionMessages,
                    BotIntegrations,
                    BotSettings,
                    BotCommands,
                    MessageRecords,
                    UserBlocks
            )

        }

        if (public) initFunction("help")

        addHandler(LocaleSwitcher(DATA_SWITCH_LOCALE) { userId, chatId, message ->

            onLaunch(userId, chatId, message)

        })

        addHandler(CreateBot())
        addHandler(MyBots())

        addHandler(InputHandler(this))
        addHandler(OutputHandler(this))
        addHandler(EditHandler(this))
        addHandler(DeleteHandler(this))
        addHandler(JoinHandler(this))
        addHandler(RecallHandler(this))

        addHandler(GetIdCommand())
        addHandler(AdminCommands())

    }

    suspend fun updateCommands() {

        val commands = database {
            BotCommands
                    .select { commandsForCurrentBot and (BotCommands.hide eq false) }
                    .map { TdApi.BotCommand(it[BotCommands.command], it[BotCommands.description]) }
                    .toTypedArray()
        }

        if (public) {

            upsertCommands(
                    CreateBot.DEF,
                    MyBots.DEF,
                    LocaleSwitcher.DEF,
                    * commands,
                    HELP_COMMAND,
                    CANCEL_COMMAND
            )

        } else {

            upsertCommands(* commands)

        }

    }

    override suspend fun skipFloodCheck(senderUserId: Int, message: TdApi.Message) = senderUserId == admin.toInt()

    override suspend fun onLogin() {

        updateCommands()

        database {

            BotInstances.loadAll()

        }

        timer.schedule(Date(nextDay()), 24 * 60 * 60 * 1000L) {

            GlobalScope.launch(Dispatchers.IO) { gc() }

        }

    }

    override suspend fun gc() {

        defaultLog.debug(">> 执行垃圾回收")

        defaultLog.debug(">> 实例缓存")

        super.gc()

        val time = (SystemClock.now() / 1000L).toInt() - 24 * 60 * 60

        database {

            val result = ActionMessages.select { ActionMessages.createAt less time }

            result.forEach { row ->

                val chatId = row[ActionMessages.userId].value.toLong()

                getChatWith(chatId) {

                    onSuccess {

                        delete(chatId, row[ActionMessages.messageId])

                    }

                }


            }

        }

        database.write {

            ActionMessages.deleteWhere { ActionMessages.createAt less time }

        }

        findHandler<MyBots>().actionMessages.clear()

        defaultLog.debug(">> 其他缓存")

        BotIntegration.Cache.clear()
        BotSetting.Cache.clear()
        StartMessages.Cache.clear()

        defaultLog.debug(">> 消息数据库")

        defaultLog.trace(">> ${me.displayNameFormatted}")

        gc(this)

        BotInstances.instanceMap.forEach {

            defaultLog.trace(">> ${it.value.me.displayNameFormatted}")

            it.value.gc()

        }

        defaultLog.debug("<< 执行垃圾回收")

    }

    const val repoName = "TdPmBot"
    const val repoUrl = "https://github.com/TdBotProject/TdPmBot"
    const val licenseUrl = "https://github.com/TdBotProject/TdPmBot/blob/master/LICENSE"

    override suspend fun onNewMessage(userId: Int, chatId: Long, message: TdApi.Message) {

        if (blocks.fetch(userId).value == true) finishEvent()

        super.onNewMessage(userId, chatId, message)

        if (public) {

            onLaunch(userId, chatId, message)

            finishEvent()

        }

    }

    override suspend fun onUndefinedFunction(userId: Int, chatId: Long, message: TdApi.Message, function: String, param: String, params: Array<String>, originParams: Array<String>) {

        if (function == "cancel") {

            if (chatId == admin) {

                super.onUndefinedFunction(userId, chatId, message, function, param, params, originParams)

            } else if (!public) {

                rejectFunction()

            }

        }

        val command = BotCommands.Cache.fetch(me.id to function).value?.takeIf { !it.hide }

        if (!message.fromPrivate) {

            if (command == null) return

            command.messages.forEach { sudo make it syncTo chatId }

            return

        } else {

            if (command == null) rejectFunction()

            command.messages.forEach { sudo make it syncTo chatId }

            if (chatId != admin && !public) writePersist(userId, PERSIST_UNDER_FUNCTION, 0L, function)

        }

    }

    override suspend fun onUndefinedPayload(userId: Int, chatId: Long, message: TdApi.Message, payload: String, params: Array<String>) {

        if (message.fromPrivate) {

            val command = BotCommands.Cache.fetch(me.id to payload).value?.takeIf { !it.hide } ?: rejectFunction()

            command.messages.forEach { sudo make it syncTo chatId }

            if (!public && chatId != admin) writePersist(userId, PERSIST_UNDER_FUNCTION, 0L, payload)

        } else rejectFunction()

    }

    override suspend fun onLaunch(userId: Int, chatId: Long, message: TdApi.Message) {

        if (!message.fromPrivate) return

        val L = LocaleController.forChat(userId)

        val startMessages = StartMessages.Cache.fetch(me.id).value

        if (!public && chatId != admin) {

            if (startMessages == null) {

                sudo make L.DEFAULT_WELCOME sendTo chatId

            } else {

                startMessages.forEach {

                    sudo make it syncTo chatId

                }

            }

            return

        }

        if (LocaleController.chatLangMap.fetch(chatId).value == null) {

            findHandler<LocaleSwitcher>().startSelect(L, chatId, true)

            return

        }

        sudo makeHtml L.LICENSE.input(repoName, licenseUrl, "Github Repo".toLink(repoUrl)) syncTo chatId

        delay(600L)

        if (startMessages == null) {

            sudo make L.HELP_MSG sendTo chatId

        } else {

            startMessages.forEach {

                sudo make it syncTo chatId

            }

        }

    }

    override suspend fun onFunction(userId: Int, chatId: Long, message: TdApi.Message, function: String, param: String, params: Array<String>, originParams: Array<String>) {

        val L = LocaleController.forChat(userId)

        sudo make L.HELP_MSG sendTo chatId

    }

}