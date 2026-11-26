package retail;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores/{storeId}")
class InventoryController {
    private final InventoryService inventory;

    InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    record SaleRequest(@NotNull LocalDate date,
                       @NotEmpty Map<@NotBlank String, @NotNull @Positive Integer> items) {}

    @PostMapping("/sales")
    @ResponseStatus(HttpStatus.CREATED)
    InventoryService.Sale sell(@PathVariable @Positive long storeId,
                               @Valid @RequestBody SaleRequest request) {
        return inventory.sell(storeId, request.date(), request.items());
    }

    @GetMapping("/expiry-alerts")
    List<InventoryService.Alert> alerts(@PathVariable @Positive long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "2") @PositiveOrZero int days) {
        return inventory.alerts(storeId, date, days);
    }

    @GetMapping("/replenishment")
    List<InventoryService.Recommendation> replenishment(@PathVariable @Positive long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return inventory.replenishment(storeId, date);
    }

    @ExceptionHandler(InventoryService.StoreNotFound.class)
    ProblemDetail missingStore(InventoryService.StoreNotFound error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, error.getMessage());
    }

    @ExceptionHandler(InventoryService.SaleRejected.class)
    ProblemDetail rejectedSale(InventoryService.SaleRejected error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, error.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidInput(IllegalArgumentException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage());
    }
}
