package com.cloudwms.core.inventory.domain;

public enum LocationType {

	/** Pickers pick from here; replenished from reserve. */
	FORWARD_PICK,
	/** Bulk storage, usually above or behind forward-pick; the source for replenishment. */
	RESERVE,
	STAGING,
	PACK,
	DOCK

}
