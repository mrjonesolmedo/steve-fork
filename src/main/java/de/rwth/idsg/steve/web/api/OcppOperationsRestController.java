/*
 * SteVe - SteckdosenVerwaltung - https://github.com/steve-community/steve
 * Copyright (C) 2013-2019 RWTH Aachen University - Information Systems - Intelligent Distributed Systems Group (IDSG).
 * All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package de.rwth.idsg.steve.web.api;

import de.rwth.idsg.steve.ocpp.CommunicationTask;
import de.rwth.idsg.steve.ocpp.OcppTransport;
import de.rwth.idsg.steve.ocpp.RequestResult;
import de.rwth.idsg.steve.repository.dto.ChargePointSelect;
import de.rwth.idsg.steve.repository.dto.OcppTag;
import de.rwth.idsg.steve.repository.TaskStore;
import de.rwth.idsg.steve.repository.TransactionRepository;
import de.rwth.idsg.steve.repository.OcppTagRepository;
import de.rwth.idsg.steve.service.ChargePointService16_Client;
import de.rwth.idsg.steve.service.TransactionStopService;
import de.rwth.idsg.steve.web.api.ApiControllerAdvice.ApiErrorResponse;
import de.rwth.idsg.steve.web.api.exception.NotFoundException;
import de.rwth.idsg.steve.web.dto.ocpp.RemoteStartTransactionParams;
import de.rwth.idsg.steve.web.dto.ocpp.RemoteStopTransactionParams;
import de.rwth.idsg.steve.web.dto.OcppTagForm;
import de.rwth.idsg.steve.web.dto.OcppTagQueryForm;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Optional;
import java.util.Objects;

// Based off https://github.com/redhell/steve/commits/REST_Branch

@Slf4j
@RestController
@RequestMapping(value = "/api/v1/ocppOperations", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class OcppOperationsRestController {

    private final ChargePointService16_Client cp16client;
    private final TaskStore taskStore;
    private final TransactionRepository transactionRepository;
    private final TransactionStopService transactionStopService;
    private final OcppTagRepository ocppTagRepository;

    @ApiResponses(value = {
        @ApiResponse(code = 200, message = "OK"),
        @ApiResponse(code = 400, message = "Bad Request", response = ApiErrorResponse.class),
        @ApiResponse(code = 401, message = "Unauthorized", response = ApiErrorResponse.class),
        @ApiResponse(code = 404, message = "Not Found", response = ApiErrorResponse.class),
        @ApiResponse(code = 500, message = "Internal Server Error", response = ApiErrorResponse.class)}
    )
    @PostMapping("/{chargeBoxId}/remoteStartTransaction/{idTag}")
    @ResponseStatus(HttpStatus.OK)
    public void remoteStartTransaction(@PathVariable("chargeBoxId") String chargeBoxId, @PathVariable("idTag") String idTag) throws Exception {
        log.info("remoteStartTransaction API request: chargeBoxId {}, idTag {}", chargeBoxId, idTag);

        RemoteStartTransactionParams params = new RemoteStartTransactionParams();
        params.setIdTag(idTag);
        params.setConnectorId(0);

        List<ChargePointSelect> cplist = new ArrayList<>();
        ChargePointSelect cps = new ChargePointSelect(OcppTransport.JSON, chargeBoxId);
        cplist.add(cps);
        params.setChargePointSelectList(cplist);
        log.info("RemoteStartTransactionParams: {}", params);

        int taskId = cp16client.remoteStartTransaction(params);
        log.info("taskId: {}", taskId);
        CommunicationTask task = taskStore.get(taskId);
        log.info("task: {}", task);

        while (!task.isFinished() || task.getResultMap().size() > 1) {
            log.info("remoteStartTransaction task in progress");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        RequestResult ocppResult = (RequestResult) task.getResultMap().get(chargeBoxId);
        log.info("ocppResult: {}", ocppResult);
        if (ocppResult.getResponse() == null) {
            log.info("ocppResult response null");
            throw new Exception("ocppResult response null");
        } else if (!ocppResult.getResponse().equals("Accepted")) {
            log.info("ocppResult response not accepted");
            throw new Exception("ocPPResult response not accepted");
        } else {
            log.info("ocppResult response accepted");
        }
    }

    @ApiResponses(value = {
        @ApiResponse(code = 200, message = "OK"),
        @ApiResponse(code = 400, message = "Bad Request", response = ApiErrorResponse.class),
        @ApiResponse(code = 401, message = "Unauthorized", response = ApiErrorResponse.class),
        @ApiResponse(code = 404, message = "Not Found", response = ApiErrorResponse.class),
        @ApiResponse(code = 500, message = "Internal Server Error", response = ApiErrorResponse.class)}
    )
    @PostMapping("/{chargeBoxId}/remoteStopTransaction/{idTag}")
    @ResponseStatus(HttpStatus.OK)
    public void remoteStopTransaction(@PathVariable("chargeBoxId") String chargeBoxId, @PathVariable("idTag") String idTag) throws Exception {
        log.info("remoteStopTransaction API request: chargeBoxId {}, idTag {}", chargeBoxId, idTag);

        RemoteStopTransactionParams params = new RemoteStopTransactionParams();
        List<Integer> transactionIDs = transactionRepository.getActiveTransactionIds(chargeBoxId);
        log.info("transactionIds associated with chargeBoxId: {}", transactionIDs);

        if (transactionIDs.size() > 0) {
            List<String> tokenList = new ArrayList<>();
            getTokenList(idTag).forEach(token -> tokenList.add(token.get(0)));
            log.info("tokenList associated with idTag: {}", tokenList);
            if (tokenList.contains(transactionRepository.getDetails(transactionIDs.get(transactionIDs.size() - 1)).getTransaction().getOcppIdTag())) {
                params.setTransactionId(transactionIDs.get(transactionIDs.size() - 1));
                List<ChargePointSelect> cplist = new ArrayList<>();
                ChargePointSelect cps = new ChargePointSelect(OcppTransport.JSON, chargeBoxId);
                cplist.add(cps);
                params.setChargePointSelectList(cplist);
                log.info("RemoteStopTransactionParams: {}", params);
        
                int taskId = cp16client.remoteStopTransaction(params);
                log.info("taskId: {}", taskId);
                CommunicationTask task = taskStore.get(taskId);
                log.info("task: {}", task);

                while (!task.isFinished() || task.getResultMap().size() > 1) {
                    log.info("remoteStopTransaction task in progress");
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                RequestResult ocppResult = (RequestResult) task.getResultMap().get(chargeBoxId);
                log.info("ocppResult: {}", ocppResult);

                transactionStopService.stop(transactionIDs);
            } else {
                log.info("Charging session not associated with tag");
                throw new Exception("Charging session not associated with tag");
            }
        } else {
            log.info("No transaction IDs");
            throw new Exception("No transaction IDs");
        }
    }

    private List<List<String>> getTokenList(String ocpp_parent) throws NullPointerException {
        List<String> ocppTagList = ocppTagRepository.getIdTags()
                .stream()
                .filter(tag -> Objects.equals(ocppTagRepository.getParentIdtag(tag), ocpp_parent))
                .collect(Collectors.toList());
        if (!ocppTagList.contains(ocpp_parent)) {
            ocppTagList.add(0, ocpp_parent);
        }
        List<List<String>> responseList = new ArrayList<>();

        ocppTagList.forEach(tag -> {
            OcppTagQueryForm ocppTagQueryForm = new OcppTagQueryForm();
            ocppTagQueryForm.setIdTag(tag);
            String note;
            Optional<OcppTag.Overview> optionalOverview = ocppTagRepository.getOverview(ocppTagQueryForm).stream().findFirst();
            if (optionalOverview.isPresent()) {
                note = ocppTagRepository.getRecord(optionalOverview.get().getOcppTagPk()).getNote();
                if (note == null) {
                    note = "";
                }
                responseList.add(Stream.of(tag, note).collect(Collectors.toList()));
            } else {
                throw new NullPointerException();
            }

        });
        return responseList;
    }
    
}
