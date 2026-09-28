package com.necora.aicompanion.net;

import com.necora.aicompanion.AICompanionMod;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * The two packets behind the companion screens. Both carry a short kind string plus a JSON
 * object, which keeps the protocol easy to extend without a packet class per button.
 */
public final class GuiPayloads {
	private GuiPayloads() {
	}

	/** Client -> server: "do this" or "tell me about that". */
	public record Request(String action, String data) implements CustomPayload {
		public static final CustomPayload.Id<Request> ID = new CustomPayload.Id<>(Identifier.of(AICompanionMod.MOD_ID, "gui_request"));
		public static final PacketCodec<RegistryByteBuf, Request> CODEC = PacketCodec.tuple(
				PacketCodecs.string(64), Request::action,
				PacketCodecs.string(30000), Request::data,
				Request::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** Server -> client: state for the open screen, a notification, or "open the screen for X". */
	public record Update(String kind, String data) implements CustomPayload {
		public static final CustomPayload.Id<Update> ID = new CustomPayload.Id<>(Identifier.of(AICompanionMod.MOD_ID, "gui_update"));
		public static final PacketCodec<RegistryByteBuf, Update> CODEC = PacketCodec.tuple(
				PacketCodecs.string(64), Update::kind,
				PacketCodecs.string(262144), Update::data,
				Update::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public static void register() {
		PayloadTypeRegistry.playC2S().register(Request.ID, Request.CODEC);
		PayloadTypeRegistry.playS2C().register(Update.ID, Update.CODEC);
	}
}
