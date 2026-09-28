package com.necora.aicompanion.entity;

import java.util.Locale;

public enum Stance {
	/** Never starts fights, doesn't even fight back. */
	PASSIVE,
	/** Fights back and defends its owner. */
	DEFENSIVE,
	/** Also attacks any hostile mob that comes close. */
	AGGRESSIVE;

	public static Stance parse(String s, Stance fallback) {
		if (s == null) return fallback;
		String v = s.trim().toLowerCase(Locale.ROOT);
		if (v.startsWith("pass") || v.equals("peaceful") || v.equals("calm")) return PASSIVE;
		if (v.startsWith("agg") || v.equals("hostile") || v.equals("attack") || v.equals("hunt")) return AGGRESSIVE;
		if (v.startsWith("def") || v.equals("normal") || v.equals("guard")) return DEFENSIVE;
		return fallback;
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}
}
