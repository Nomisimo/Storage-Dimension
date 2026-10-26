package de.nomisimo.storagedim.client;

import net.minecraft.client.renderer.entity.state.HoldingEntityRenderState;

public class RobotRenderState extends HoldingEntityRenderState {
	public int color;
	/** 0 = nothing, 1 = picking up, 2 = handing in. */
	public int action;
	public float actionProgress;
	public boolean sitting;
	/** {@link de.nomisimo.storagedim.robot.RobotEntity#MOOD_HAPPY} or MOOD_SAD while sitting. */
	public int mood;
	public boolean carrying;
}
