package fr.gouv.tchap.matrix.core.message;

import fr.gouv.tchap.matrix.message.TransactionIdGenerator;
import java.util.UUID;

public class UuidTransactionIdGenerator implements TransactionIdGenerator {
    @Override
    public String next() {
        return UUID.randomUUID().toString();
    }
}