package org.qosp.notes.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Applied OS scheduling fingerprint; desired state remains the committed reminder/note graph. */
@Entity(tableName = "reminder_alarm_state")
data class ReminderAlarmState(@PrimaryKey val id:Long,val noteId:Long,val date:Long,val name:String,val fingerprint:String)
