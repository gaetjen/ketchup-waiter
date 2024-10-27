package dev.mtib.ketchup.bot.interactions.handlers

import dev.kord.core.Kord
import dev.kord.core.behavior.channel.asChannelOf
import dev.kord.core.behavior.channel.edit
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.event.interaction.ActionInteractionCreateEvent
import dev.kord.rest.builder.interaction.GlobalChatInputCreateBuilder
import dev.kord.rest.builder.interaction.integer
import dev.mtib.ketchup.bot.features.planner.Planner
import dev.mtib.ketchup.bot.interactions.interfaces.Interaction
import dev.mtib.ketchup.bot.interactions.interfaces.Interaction.Companion.Visibility
import dev.mtib.ketchup.bot.interactions.interfaces.Interaction.Companion.Visibility.PUBLIC
import dev.mtib.ketchup.bot.interactions.interfaces.Interaction.Companion.getNumberOptionByName
import java.time.Instant
import java.time.ZoneId

object ScheduleEventChannel : Interaction {
    override val visibility: Visibility = PUBLIC
    override val name: String = "schedule_event"
    override val description: String = "Schedule the event of an event channel"

    override suspend fun build(it: GlobalChatInputCreateBuilder) {
        super.build(it)
        it.integer("year", "Year of the event")
        it.integer("month", "Month of the event")
        it.integer("day", "Day of the event")
        it.dmPermission = false
    }

    override suspend fun handleInteraction(event: ActionInteractionCreateEvent, kord: Kord) {
        val response = event.defer()
        val year = event.interaction.getNumberOptionByName("year")?.toInt()!!
        val month = event.interaction.getNumberOptionByName("month")?.toInt()!!
        val day = event.interaction.getNumberOptionByName("day")?.toInt()!!

        val date = Instant.now().atZone(ZoneId.of("Europe/Copenhagen"))
            .withYear(year)
            .withMonth(month)
            .withDayOfMonth(day)
            .withHour(14)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)

        val channel = event.interaction.channel.asChannelOf<TextChannel>()
        if (channel.categoryId != Planner.locations.upcomingEventsSnowflake) {
            response.respond {
                content = "This command can only be used in event channels"
            }
            return
        }

        val newName = channel.name.let {
            when {
                it.startsWith("idea-") -> "$date-${it.substring(5)}"
                it.matches(Regex("""\d{4}-\d{2}-\d{2}-.*""")) -> "$date-${it.substring(11)}"
                else -> "$date-$it"
            }
        }
        if (newName != channel.name) {
            channel.edit {
                name = newName
            }
            Planner.organiseEventChannels(kord)
        }

        response.respond {
            content = "Event scheduled for $date"
        }
    }
}