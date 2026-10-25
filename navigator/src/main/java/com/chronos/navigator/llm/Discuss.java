package com.chronos.navigator.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.util.List;

/**
 * A conversation about one proposal. You ask why, object, or suggest a
 * change; the model answers with the original question and snapshot in front
 * of it, its own earlier proposal, and the conversation so far. When what you
 * say warrants it, it revises the proposal in the same shape the original
 * question asked for — the revision then goes through the same checks and the
 * same measurement of its effect, and waits for you like any proposal.
 */
public final class Discuss {
    private Discuss() {}

    /** One turn of the conversation: "you" or "model". */
    public record Turn(String who, String text) {}

    /**
     * What the model said: its reply, and a revised answer in the original shape, or null.
     * {@code obsolete}: asked to re-check it, the model says it no longer helps as things are now.
     */
    public record Answer(String reply, JsonObject revised, boolean involvesOtherPerson, boolean obsolete) {
        public Answer(String reply, JsonObject revised, boolean involvesOtherPerson) { this(reply, revised, involvesOtherPerson, false); }
    }

    /** What the inbox's re-check says to the model about one proposal; the snapshot in front of it is today's. */
    public static String recheckMessage(LocalDate today, LocalDate proposedOn, String news) {
        return "Re-check, " + today + ": you proposed this on " + proposedOn + ". The snapshot above is the situation as it is "
            + "today, and has changed since." + (news == null || news.isBlank() ? "" : " " + news)
            + " Look at your proposal against today. If it no longer helps, say why in one sentence and set obsolete to true. "
            + "If it still helps but should change, revise it. If it is right as it is, say so.";
    }

    static final String RULES = String.join("\n",
        "",
        "You are now discussing a proposal you made with the student it is for.",
        "- Answer what they ask, plainly and briefly (at most five sentences), in the language they write in.",
        "- Explain your reasoning from the snapshot and general knowledge; say which assumptions drive the numbers.",
        "- If they are right, or partly right, revise the proposal. If you still think your proposal is better, say why and keep it; you may also do both: keep the substance and adjust a number.",
        "- Never invent what the snapshot does not say. If you need information the student has, ask for it.",
        "- Asked to re-check it against today: if it no longer helps at all, set obsolete to true (and revise to false).",
        "- Finish with exactly one JSON object and nothing after it:",
        "  {\"reply\":\"…\",\"revise\":false,\"obsolete\":false,\"revised\":null,\"consent_self_check\":{\"involves_other_person\":false}}",
        "  When revise is true, \"revised\" is a complete answer in exactly the shape the original question asked for (the same fields as your earlier answer).");

    /**
     * @param original the question as it would be asked now (rebuilt from the graph as it is)
     * @param earlier  the proposal as filed: its summary, rationale and what it would change, in words
     */
    public static LlmRequest request(LlmRequest original, String earlier, List<Turn> thread, String message) {
        StringBuilder u = new StringBuilder(original.user());
        u.append("\n\nYour earlier proposal:\n").append(earlier);
        if (!thread.isEmpty()) {
            u.append("\n\nThe conversation so far:");
            for (Turn t : thread) u.append("\n").append(t.who().equals("you") ? "Student: " : "You: ").append(t.text());
        }
        u.append("\n\nStudent: ").append(message);
        JsonObject target = original.target().deepCopy();
        target.addProperty("discussion", true);
        return new LlmRequest(original.task(), target, original.system() + RULES, u.toString(), original.webSearch());
    }

    /** Reads the model's answer; null when it holds no usable reply. */
    public static Answer read(String text) {
        JsonObject o = ProposalValidator.extractJson(text);
        if (o == null) {
            // Some models answer in prose only: keep what they said, with nothing to revise.
            String plain = text == null ? "" : text.trim();
            return plain.isEmpty() ? null : new Answer(plain, null, false);
        }
        String reply = ProposalValidator.str(o, "reply");
        boolean revise = o.has("revise") && o.get("revise").isJsonPrimitive() && o.get("revise").getAsBoolean();
        boolean obsolete = !revise && o.has("obsolete") && o.get("obsolete").isJsonPrimitive()
            && o.get("obsolete").getAsJsonPrimitive().isBoolean() && o.get("obsolete").getAsBoolean();
        JsonElement r = o.get("revised");
        JsonObject revised = revise && r != null && r.isJsonObject() ? r.getAsJsonObject() : null;
        boolean other = false;
        if (o.has("consent_self_check") && o.get("consent_self_check").isJsonObject()) {
            JsonElement v = o.getAsJsonObject("consent_self_check").get("involves_other_person");
            other = v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean();
        }
        // A revision must carry its own consent self-check for the validator; it inherits the reply's.
        if (revised != null && !revised.has("consent_self_check") && o.has("consent_self_check"))
            revised.add("consent_self_check", o.get("consent_self_check").deepCopy());
        if (reply == null && revised == null) return null;
        return new Answer(reply == null ? "" : reply, revised, other, obsolete);
    }
}
