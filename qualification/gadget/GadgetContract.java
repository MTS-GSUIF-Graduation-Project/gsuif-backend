package qualification.gadget;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

enum GadgetStatus { OPEN, CLOSED }
record GadgetDto(UUID id, String title, GadgetStatus status, String note) {}
record CreateGadgetRequest(String title, GadgetStatus status, String note) {}
record UpdateGadgetRequest(String title, GadgetStatus status, String note) {}

interface GadgetRepository {
    List<GadgetDto> findAll();
    Optional<GadgetDto> findById(UUID id);
    GadgetDto save(GadgetDto gadget);
    void deleteById(UUID id);
}

interface GadgetService {
    List<GadgetDto> fetchGadgets();
    GadgetDto submitGadget(CreateGadgetRequest request);
    GadgetDto findGadget(UUID id);
    GadgetDto replaceGadget(UUID id, UpdateGadgetRequest request);
    void removeGadget(UUID id);
}

final class GadgetNotFoundException extends RuntimeException {
    GadgetNotFoundException() { super("Gadget not found"); }
}
