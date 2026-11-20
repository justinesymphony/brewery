package com.symphony.workshop.brewery

/** A beer as returned by the /v1/beers endpoint. */
data class Beer(
	val id: Long,
	val name: String,
	val brewery: String
)
