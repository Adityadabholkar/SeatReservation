package com.example.seats.service.impl;

import com.example.seats.dto.CreateShowRequest;
import com.example.seats.dto.SeatView;
import com.example.seats.dto.ShowCounts;
import com.example.seats.dto.ShowResponse;
import com.example.seats.exception.ApiException;
import com.example.seats.metrics.SeatGauges;
import com.example.seats.model.ShowRecord;
import com.example.seats.repository.SeatRepository;
import com.example.seats.repository.ShowRepository;
import com.example.seats.service.ShowService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ShowServiceImpl implements ShowService {

    private static final Logger log = LoggerFactory.getLogger(ShowServiceImpl.class);
    private static final int MAX_SEATS = 100_000;
    private static final int MAX_LABEL_LENGTH = 64;
    private static final int DEFAULT_PER_USER_LIMIT = 4;

    private final ShowRepository shows;
    private final SeatRepository seats;
    private final SeatGauges gauges;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public ShowServiceImpl(ShowRepository shows,
                           SeatRepository seats,
                           SeatGauges gauges,
                           TransactionTemplate tx,
                           PlatformTransactionManager tm) {
        this.shows = shows;
        this.seats = seats;
        this.gauges = gauges;
        this.tx = tx;
        // REPEATABLE READ: every query inside sees ONE snapshot, so seats + counts always reconcile
        this.readTx = new TransactionTemplate(tm);
        this.readTx.setReadOnly(true);
        this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public ShowResponse create(CreateShowRequest req) {
        if (req == null) {
            throw ApiException.badRequest("INVALID_REQUEST", "request body is required");
        }
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) {
            throw ApiException.badRequest("INVALID_REQUEST", "name is required");
        }
        if (req.pricePaise() == null || req.pricePaise() < 0) {
            throw ApiException.badRequest("INVALID_REQUEST", "price_paise is required and must be a non-negative integer");
        }
        int limit = req.perUserLimit() == null ? DEFAULT_PER_USER_LIMIT : req.perUserLimit();
        if (limit < 1) {
            throw ApiException.badRequest("INVALID_REQUEST", "per_user_limit must be >= 1");
        }
        List<String> labels = cleanLabels(req.seats());

        UUID id = UUID.randomUUID();
        ShowRecord show = new ShowRecord(id, name, req.pricePaise(), limit, labels.size(), Instant.now());
        tx.executeWithoutResult(status -> {
            shows.insert(show);
            seats.insertAll(id, labels);
        });
        gauges.register(id);

        List<SeatView> views = new ArrayList<>(labels.size());
        for (String l : labels) {
            views.add(new SeatView(l, "available"));
        }
        long total = labels.size();
        return new ShowResponse(id, name, show.pricePaise(), limit, labels.size(),
                total, 0L, 0L, new ShowCounts(total, 0L, 0L), views);
    }

    @Override
    public ShowResponse get(UUID showId, boolean includeSeats) {
        ShowResponse response = readTx.execute(status -> {
            ShowRecord show = shows.findById(showId)
                    .orElseThrow(() -> ApiException.notFound("SHOW_NOT_FOUND", "show not found"));
            long available = 0;
            long held = 0;
            long confirmed = 0;
            List<SeatView> list = null;
            if (includeSeats) {
                list = seats.listSeats(showId);
                for (SeatView v : list) {
                    if ("available".equals(v.status())) {
                        available++;
                    } else if ("held".equals(v.status())) {
                        held++;
                    } else if ("confirmed".equals(v.status())) {
                        confirmed++;
                    }
                }
            } else {
                Map<String, Long> m = seats.countByStatus(showId);
                available = m.getOrDefault("available", 0L);
                held = m.getOrDefault("held", 0L);
                confirmed = m.getOrDefault("confirmed", 0L);
            }
            if (available + held + confirmed != show.totalSeats()) {
                log.error("RECONCILIATION INVARIANT VIOLATED show={} available={} held={} confirmed={} total={}",
                        showId, available, held, confirmed, show.totalSeats());
            }
            return new ShowResponse(show.id(), show.name(), show.pricePaise(), show.perUserLimit(),
                    show.totalSeats(), available, held, confirmed,
                    new ShowCounts(available, held, confirmed), list);
        });
        gauges.register(showId); // covers shows created by another instance
        return response;
    }

    private static List<String> cleanLabels(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw ApiException.badRequest("INVALID_REQUEST", "seats must be a non-empty list");
        }
        if (raw.size() > MAX_SEATS) {
            throw ApiException.badRequest("INVALID_REQUEST", "too many seats (max " + MAX_SEATS + ")");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String s : raw) {
            String label = s == null ? "" : s.trim();
            if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH) {
                throw ApiException.badRequest("INVALID_REQUEST",
                        "every seat label must be 1-" + MAX_LABEL_LENGTH + " characters");
            }
            if (!unique.add(label)) {
                throw ApiException.badRequest("INVALID_REQUEST", "duplicate seat label: " + label);
            }
        }
        return new ArrayList<>(unique);
    }
}
