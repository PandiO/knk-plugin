package net.knightsandkings.knk.paper.production.harvest;

/** Test access: sends the outbox again now, skipping the retry pause. */
final class HarvestModuleTestAccess {

    private HarvestModuleTestAccess() {
    }

    static void retryNow(HarvestModule module) {
        var outbox = module.outbox();
        var keys = new java.util.ArrayList<String>();
        for (String line : outbox.encode()) {
            keys.add(line.substring(0, line.indexOf('\t')));
        }
        java.util.List<String> lines = outbox.encode();
        keys.forEach(outbox::answered);
        outbox.load(lines, null); // loaded entries are ready at once
        module.flush();
    }
}
