package qualification.gadget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class GadgetBehaviorCheck {
    public static void main(String[] args) {
        var repository = new MemoryRepository();
        GadgetService service = new GadgetServiceImpl(repository);
        if (!service.fetchGadgets().isEmpty()) fail("initial list");
        var created = service.submitGadget(new CreateGadgetRequest("one", GadgetStatus.OPEN, "note"));
        if (created.id() == null || !"one".equals(created.title()) || created.status() != GadgetStatus.OPEN) fail("create");
        if (!created.equals(service.findGadget(created.id())) || service.fetchGadgets().size() != 1) fail("read/list");
        var replaced = service.replaceGadget(created.id(), new UpdateGadgetRequest("two", GadgetStatus.CLOSED, null));
        if (!created.id().equals(replaced.id()) || !"two".equals(replaced.title()) ||
                replaced.status() != GadgetStatus.CLOSED || replaced.note() != null) fail("full update/null");
        if (!replaced.equals(service.findGadget(created.id()))) fail("update persistence");
        service.removeGadget(created.id());
        if (!service.fetchGadgets().isEmpty()) fail("hard delete");
        expectMissing(() -> service.findGadget(created.id()));
        expectMissing(() -> service.replaceGadget(created.id(), new UpdateGadgetRequest("x", GadgetStatus.OPEN, null)));
        expectMissing(() -> service.removeGadget(created.id()));
        System.out.println("GSUIF_GADGET_CHECK_COMPLETE:" + args[0]);
    }

    private static void expectMissing(Runnable action) {
        try { action.run(); } catch (GadgetNotFoundException expected) { return; }
        fail("missing record");
    }

    private static void fail(String check) { throw new AssertionError(check); }

    private static final class MemoryRepository implements GadgetRepository {
        private final Map<UUID, GadgetDto> rows = new LinkedHashMap<>();
        public List<GadgetDto> findAll() { return new ArrayList<>(rows.values()); }
        public Optional<GadgetDto> findById(UUID id) { return Optional.ofNullable(rows.get(id)); }
        public GadgetDto save(GadgetDto gadget) { rows.put(gadget.id(), gadget); return gadget; }
        public void deleteById(UUID id) { rows.remove(id); }
    }
}
