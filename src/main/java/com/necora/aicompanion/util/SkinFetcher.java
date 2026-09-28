package com.necora.aicompanion.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Looks up a real player's skin (signed textures property) from Mojang's public API. */
public final class SkinFetcher {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	public record Skin(String name, String value, String signature) {
	}

	private SkinFetcher() {
	}

	public static CompletableFuture<Optional<Skin>> fetch(String playerName) {
		String name = playerName.trim();
		if (!name.matches("[A-Za-z0-9_]{2,16}")) return CompletableFuture.completedFuture(Optional.empty());
		HttpRequest profileReq = HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
				.timeout(Duration.ofSeconds(10)).GET().build();
		return HTTP.sendAsync(profileReq, HttpResponse.BodyHandlers.ofString()).thenCompose(resp -> {
			if (resp.statusCode() != 200) return CompletableFuture.completedFuture(Optional.<Skin>empty());
			JsonObject profile = JsonParser.parseString(resp.body()).getAsJsonObject();
			String id = profile.get("id").getAsString();
			String realName = profile.has("name") ? profile.get("name").getAsString() : name;
			HttpRequest texReq = HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false"))
					.timeout(Duration.ofSeconds(10)).GET().build();
			return HTTP.sendAsync(texReq, HttpResponse.BodyHandlers.ofString()).thenApply(r2 -> {
				if (r2.statusCode() != 200) return Optional.<Skin>empty();
				JsonObject o = JsonParser.parseString(r2.body()).getAsJsonObject();
				JsonArray props = o.getAsJsonArray("properties");
				if (props == null) return Optional.<Skin>empty();
				for (JsonElement p : props) {
					JsonObject po = p.getAsJsonObject();
					if ("textures".equals(po.get("name").getAsString())) {
						String sig = po.has("signature") ? po.get("signature").getAsString() : "";
						return Optional.of(new Skin(realName, po.get("value").getAsString(), sig));
					}
				}
				return Optional.<Skin>empty();
			});
		}).exceptionally(t -> Optional.empty());
	}
}
