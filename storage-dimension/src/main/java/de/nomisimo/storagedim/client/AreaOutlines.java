package de.nomisimo.storagedim.client;

import java.util.List;

import de.nomisimo.storagedim.robot.Areas;
import de.nomisimo.storagedim.robot.RemoteItem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Holding a remote (either hand) shows the antenna areas around: a thick outline in the area's colour and faintly
 * shimmering faces, with the area's name above it. Switched off or paused areas are drawn grey.
 */
@Environment(EnvType.CLIENT)
public final class AreaOutlines {
	private static final int OFF_COLOR = 0x9A9A9A;
	private static List<Areas.Info> areas = List.of();

	private AreaOutlines() {
	}

	public static void register() {
		LevelRenderEvents.END_EXTRACTION.register(AreaOutlines::draw);
	}

	/** The areas around the player, sent by the server while a remote is held. */
	public static void receive(Areas.OutlinePayload payload) {
		areas = payload.areas();
	}

	private static void draw(LevelExtractionContext context) {
		LocalPlayer player = Minecraft.getInstance().player;

		if (player == null || areas.isEmpty()
				|| !RemoteItem.isRemote(player.getMainHandItem()) && !RemoteItem.isRemote(player.getOffhandItem())) {
			return;
		}

		float time = (player.tickCount + context.deltaTracker().getGameTimeDeltaPartialTick(false)) / 20F;

		try (Gizmos.TemporaryCollection ignored = context.levelRenderer().collectPerFrameGizmos()) {
			for (Areas.Info area : areas) {
				int rgb = area.active() ? area.color() : OFF_COLOR;
				// the faces shimmer slowly, each area a little out of step with the others
				float shimmer = 0.5F + 0.5F * Mth.sin(time * 2F + (area.station() & 0xFF) * 0.4F);
				int fillAlpha = (int) (0x18 + 0x20 * shimmer);
				AABB box = area.box();
				Gizmos.cuboid(box, GizmoStyle.strokeAndFill(0xFF000000 | rgb, 4F, fillAlpha << 24 | rgb), false);
				String label = area.name() + (area.enabled() ? area.paused() ? " (Pause)" : "" : " (aus)");
				Gizmos.billboardText(label, new Vec3(box.getCenter().x, box.maxY + 0.6, box.getCenter().z),
						TextGizmo.Style.forColorAndCentered(0xFF000000 | rgb).withScale(TextGizmo.Style.DEFAULT_SCALE * 2));
			}
		}
	}
}
