package com.anymindgroup.pubsub

final case class TopicName(projectId: String, topic: String) {
  def fullName: String          = s"projects/$projectId/topics/$topic"
  override def toString: String = fullName
}
