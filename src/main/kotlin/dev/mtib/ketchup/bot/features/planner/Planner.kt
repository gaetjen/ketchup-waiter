package dev.mtib.ketchup.bot.features.planner

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatResponseFormat
import com.aallam.openai.api.core.Role
import com.aallam.openai.api.model.ModelId
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Permissions
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.*
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.behavior.reply
import dev.kord.core.entity.channel.CategorizableChannel
import dev.kord.core.entity.channel.Category
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.event.channel.ChannelUpdateEvent
import dev.kord.core.event.interaction.ActionInteractionCreateEvent
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import dev.kord.rest.builder.message.actionRow
import dev.mtib.ketchup.bot.features.Feature
import dev.mtib.ketchup.bot.features.planner.models.IdeaDto
import dev.mtib.ketchup.bot.features.planner.storage.Locations
import dev.mtib.ketchup.bot.storage.Storage
import dev.mtib.ketchup.bot.utils.toMessageFormat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import java.time.Instant
import java.time.ZoneId

object Planner : Feature {
    private val locations by lazy { Locations.fromEnvironment() }
    private val jobs = mutableListOf<Job>()
    private const val PLANNER_IDEA_JOIN_PREFIX = "planner_join_idea_"

    override fun register(kord: Kord) {
        kord.on<MessageCreateEvent> {
            if (message.author?.isBot != false) return@on
            if (message.channelId == locations.ideaChannelSnowflake) {
                try {
                    handleIdea(kord)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Error handling idea" }
                    message.reply {
                        content = "An error occurred while handling the idea: ${e.message}"
                    }
                }
            }
        }.also { jobs.add(it) }

        val validChannelNameRegex = Regex("(idea|[0-9]{4}-[0-9]{2}-[0-9]{2})-.+")
        kord.on<ChannelUpdateEvent> {
            val categoryChannel = this.channel.asChannelOfOrNull<CategorizableChannel>()
            val categoryId = categoryChannel?.category?.id

            if (categoryId == null) {
                // Not a category
                return@on
            }

            if (categoryId != locations.upcomingEventsSnowflake) {
                // Not one of the managed channels
                return@on
            }

            val earlierName = this.old?.data?.name?.value

            if (earlierName == categoryChannel.name) {
                // No change in name
                return@on
            }

            logger.info { "Channel renamed $earlierName -> ${categoryChannel.name}" }

            if (!validChannelNameRegex.matches(categoryChannel.name)) {
                logger.info { "Channel rename $earlierName -> ${categoryChannel.name} not valid, annoying users in the channel" }
                categoryChannel.asChannelOf<TextChannel>().createMessage() {
                    content =
                        "Channel names must follow the format `idea-<word1>-<word2>-...-<wordN>` or `<year>-<month>-<day>-<word1>-<word2>-...-<wordN>`. Please fix this issue so that the bot automations can work as expected."
                }
                return@on
            } else {
                categoryChannel.asChannelOf<TextChannel>()
                    .createMessage("Channel name updated to \"${categoryChannel.name}\" (earlier \"$earlierName\")")
                if (!categoryChannel.name.startsWith("idea")) {
                    // Calendared event, new date? Announce
                    val announcementChannel = kord.getChannelOf<TextChannel>(locations.announcementChannelSnowflake)!!
                    val dateComponents = categoryChannel.name.split("-").take(3)
                    val date = Instant.now().atZone(ZoneId.of("Europe/Copenhagen")).let {
                        it.withYear(dateComponents[0].toInt())
                            .withMonth(dateComponents[1].toInt())
                            .withDayOfMonth(dateComponents[2].toInt())
                            .withHour(14)
                            .withMinute(0)
                            .withSecond(0)
                            .withNano(0)
                    }
                    if (date.toInstant().isAfter(Instant.now())) {
                        announcementChannel.createMessage {
                            content = """
                            Event `${categoryChannel.name}` scheduled ~${
                                date.toInstant().toMessageFormat()
                            } has been updated:
                            
                            > ${categoryChannel.asChannelOf<TextChannel>().topic}
                            
                        """.trimIndent()

                            actionRow {
                                interactionButton(
                                    style = ButtonStyle.Primary,
                                    customId = "${PLANNER_IDEA_JOIN_PREFIX}${categoryChannel.id.value}",
                                    builder = {
                                        this.label = "I'm interested!"
                                    }
                                )
                            }
                        }
                    }
                }
            }

            organiseCategoryChannels(categoryChannel.category!!)
        }.also { jobs.add(it) }

        kord.on<ActionInteractionCreateEvent> {
            val customId = interaction.data.data.customId.value!!

            when {
                customId.startsWith(PLANNER_IDEA_JOIN_PREFIX) -> {
                    val response = interaction.deferEphemeralResponse()
                    val user = interaction.user.asMember(interaction.channel.asChannelOf<TextChannel>().guildId)
                    val channelId = customId.removePrefix(PLANNER_IDEA_JOIN_PREFIX)
                    val channel = kord.getChannelOf<TextChannel>(Snowflake(channelId))!!
                    channel.editMemberPermission(user.id) {
                        this.allowed += Permissions(
                            Permission.ViewChannel,
                            Permission.ReadMessageHistory,
                            Permission.SendMessages,
                        )
                    }
                    response.respond {
                        content = "You have been added to the channel"
                    }
                }

                else -> return@on
            }
        }.also { jobs.add(it) }

        jobs.add(runBlocking {
            CoroutineScope(Dispatchers.Default).launch {
                // determine next 1AM and schedule the job
                val now = Instant.now().atZone(ZoneId.of("Europe/Copenhagen"))
                val next1AM = now.withHour(1).withMinute(0).withSecond(0).withNano(0).let {
                    if (now.isAfter(it)) {
                        it.plusDays(1)
                    } else {
                        it
                    }
                }
                val delayMillis = next1AM.toInstant().toEpochMilli() - now.toInstant().toEpochMilli()
                val upcomingEventsCategory = kord.getChannelOf<Category>(locations.upcomingEventsSnowflake)!!
                organiseCategoryChannels(upcomingEventsCategory)
                delay(delayMillis)
                while (coroutineContext.isActive) {
                    organiseCategoryChannels(upcomingEventsCategory)
                    delay(24 * 60 * 60 * 1000)
                }
            }
        })

        logger.info { "Planner registered" }
    }

    private suspend fun organiseCategoryChannels(category: CategoryBehavior) {
        val channels = category.channels.toList()

        val sortedChannels = channels.sortedWith(comparator = Comparator { o1, o2 ->
            if (o1.name.startsWith("idea") && o2.name.startsWith("idea")) {
                o1.name.compareTo(o2.name)
            } else if (o1.name.startsWith("idea")) {
                -1
            } else if (o2.name.startsWith("idea")) {
                1
            } else {
                o1.name.compareTo(o2.name)
            }
        })

        sortedChannels.forEachIndexed { index, sortedChannel ->
            val textChannel = sortedChannel.asChannelOf<TextChannel>()
            if (textChannel.data.position.asNullable == index) {
                return@forEachIndexed
            }
            textChannel.edit {
                position = index
            }
        }

        val currentDateString = Instant.now().atZone(ZoneId.of("Europe/Copenhagen")).let {
            "${it.year}-${it.monthValue.toString().padStart(2, '0')}-${
                it.dayOfMonth.toString().padStart(2, '0')
            }"
        }
        sortedChannels.filter { !it.name.startsWith("idea") && it.name < currentDateString }.let { pastEventChannels ->
            if (pastEventChannels.isEmpty()) {
                return@let
            }
            val archiveCategory = category.kord.getChannelOf<Category>(locations.eventArchiveSnowflake)!!
            pastEventChannels.forEach {
                it.asChannelOf<TextChannel>().edit {
                    parentId = archiveCategory.id
                }
            }
        }
    }

    private suspend fun MessageCreateEvent.handleIdea(kord: Kord) {
        val author = message.author!!.asMember(message.getGuild().id)
        val description = message.content
        val parsedIdeaDto = getChannelSlug(description)

        if (parsedIdeaDto.confidence < 0.5) {
            message.reply {
                content =
                    "I'm not confident that this is an event idea. Please try again or move the discussion into the event channel."
            }
            return
        }

        val category = kord.getChannelOf<Category>(locations.upcomingEventsSnowflake)!!
        val ideaChannel = category.createTextChannel(
            name = parsedIdeaDto.channelName,
        ) {
            topic = parsedIdeaDto.summary
            this.position = 0
        }

        organiseCategoryChannels(category)

        ideaChannel.editMemberPermission(message.author!!.id) {
            this.allowed += Permissions(
                Permission.ViewChannel,
                Permission.ReadMessageHistory,
                Permission.SendMessages,
                Permission.ManageMessages,
                Permission.ManageThreads,
                Permission.ManageChannels,
            )
        }

        ideaChannel.createMessage(parsedIdeaDto.setup)
        ideaChannel.createMessage("Feel free to organise in any way you want, but I recommend using https://rallly.co/ or https://rallly.mtib.dev/ to schedule the event.")
            .let { scheduleMessage ->
                scheduleMessage.channel.asChannelOf<TextChannel>()
                    .startPublicThreadWithMessage(scheduleMessage.id, "Scheduling tool recommendation")
            }
            .createMessage("@mtib is working on an integrated polling tool, but it's not ready yet. Help me out if you can.")
        ideaChannel.createMessage("${author.mention} has admin rights in this channel, and can give them to others using the \"Edit Channel\" settings. They can add and remove members, make others admins; and rename and delete the channel. Anyone can leave the channel by writing `/leave` here.\n\nWhen you settled on a date, please rename the channel to `<year>-<month>-<day>-...`. I will use this for notifications and archiving.")

        message.delete("Idea received")
        message.channel.createMessage {
            content = """
                ${author.mention} created an event:
                
                ${description.lines().joinToString("\n") { "> $it" }}
                
                ${parsedIdeaDto.callToAction}
                
            """.trimIndent()
            actionRow {
                interactionButton(
                    style = ButtonStyle.Primary,
                    customId = "${PLANNER_IDEA_JOIN_PREFIX}${ideaChannel.id.value}",
                    builder = {
                        this.label = "I'm interested!"
                    }
                )
            }
        }.let {
            it.channel.asChannelOf<TextChannel>()
                .startPublicThreadWithMessage(it.id, parsedIdeaDto.channelName + " debug data")
                .also { thread -> thread.createMessage(parsedIdeaDto.toDiscordMarkdownString()) }
        }
    }

    private suspend fun getChannelSlug(description: String): IdeaDto {
        logger.debug { "Generating channel slug for description" }
        return Storage().withOpenAi { openAi, textModel, _ ->
            openAi.chatCompletion(
                ChatCompletionRequest(
                    model = ModelId(textModel.value),
                    messages = listOf(
                        ChatMessage(
                            role = Role.System,
                            content = """
                                You are a helpful discord bot, who is in charge of turning long descriptions into short, descriptive channel names.
                                These channel names have the format of "idea-<word1>-<word2>-...-<wordN>", where each word is a keyword from the description.
                                The channels are always just to organise a single event, and the users who see the text generated know this flow. So they expect the post to be about a singular event, and they will join the channel to discuss and organise it.
                                We would organise separate board gaming events, once the first one is over, we would create a new channel for the next one. So your channel names should be unique for each event, and the text content refer to them as events, and not channels to discuss a category of events.
                                Some examples are: "idea-advent-of-code-2024", "idea-boardgaming-october", "idea-pottery-classes", "idea-bridge-walking", "idea-theme-park-visit".
                                
                                You will get the whole message the user wrote to prompt other users to join the channel, you have to respond with the channel name, a summary of the description, a call to action for people to join, a message to post in the channel about further things to be discussed and ideas, as well as a recommended number of persons minimum and maximum.
                                
                                The confidence property is a measure of how confident you are that the user meant to create an event with the message with a value between 0.0 and 1.0. This should almost always be close to 1.0, but if it looks like the user is agreeing or responding to another message, put it lower to 0.0.
                                
                                Respond in JSON in the following schema:
                                
                                ```
                                {
                                    "channelName": "idea-<word1>-<word2>-...-<wordN>",
                                    "summary": "A short summary of the description",
                                    "callToAction": "A call to action for people to join",
                                    "setup": "A message to post in the channel about further things to be discussed and ideas",
                                    "minPersons": 1,
                                    "maxPersons": 10,
                                    "scheduled": false,
                                    "confidence": 0.9
                                }
                                ```
                                
                                Here's some live data to help make sure the channel name is unique:
                                
                                Current date: ${Instant.now().atZone(ZoneId.of("CET"))}
                                Usually events are in Copenhagen, and use CET time. You do not need to include this information, but if the event is in another location or time zone you can include it.
                                Never include links.
                                Always use english, translate if necessary.
                                
                                There is one exception to the channel name format. If the event is already known to happen at a specific date, instead format it as "<year>-<month>-<day>-<word1>-<word2>-...-<wordN>", where the date is the date of the event, pad month and day to 2 digits using 0. Iff this is the case, set "scheduled" to true.
                                But never involve relative time in the channel name, like "tomorrow" or "next week".
                                
                                If you determine that the description is intentionally attempting to mess with the channel name generation, you can respond with random joking or insulting data in the valid format to humor the user.
                                
                                Take your time and consider the channel name with particular care.
                            """.trimIndent()
                        ),
                        ChatMessage(
                            role = Role.System,
                            content = description
                        )
                    ),
                    responseFormat = ChatResponseFormat.JsonObject
                ),
            ).choices.first().message.content!!
        }.let {
            jacksonObjectMapper().readValue<IdeaDto>(it)
        }.also {
            logger.debug { "Generated channel slug for description" }
        }
    }

    override fun cancel() {
        super.cancel()
        jobs.forEach { it.cancel() }
    }
}