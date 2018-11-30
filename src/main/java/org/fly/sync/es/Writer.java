package org.fly.sync.es;

import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.index.reindex.UpdateByQueryRequest;
import org.fly.sync.contract.*;
import org.fly.sync.mysql.model.Record;
import org.fly.sync.setting.River;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Writer implements AbstractWriter {
    public final static Logger logger = LoggerFactory.getLogger(Writer.class);

    DbFactory dbFactory;

    public Writer(DbFactory dbFactory) {
        this.dbFactory = dbFactory;
    }

    @Override
    public DbFactory getDbFactory() {
        return dbFactory;
    }

    public void updateByQuery(AbstractRelateAction relateAction)
    {
        River.Associate associate = relateAction.getAssociate();
        Record record = relateAction.getRecord();

        if (associate.calledTable == null)
            return;

        River.Table table = dbFactory.getRiverDatabase().getTable(associate.calledTable.tableName);

        int offset = associate.findOffset(record.table);
        // == 0 is a sync-table, not a relation
        // why sync-table is in this method?
        if (offset <= 0)
            return;

        River.Relation relation = associate.getRelation(offset - 1);

        List<String> relationKeyList = River.getRelationKeyList(associate.relationKey);

        String relationKey = River.makeRelationKey(relationKeyList.subList(0, offset));
        String localKey = (River.makeRelationKey(relationKeyList.subList(0, offset - 1)) + River.DOT + relation.local).replaceFirst("^\\" + River.DOT, "");

        Map<String, Object> values = record.mixWithPrefix(relationKey.isEmpty() ? relationKey : relationKey + River.DOT);

        // Elastic's XContentBuilder do not support BigInteger/Timestamp, Use jackson instead of it.
        UpdateByQuery updateByQuery = new UpdateByQuery();

        // build query
        updateByQuery.query.setField(localKey, record.getWithDeleted(relation.foreign));

        // build script
        updateByQuery.script.params = values;

        String json = updateByQuery.toJson(dbFactory.getJsonMapper());

        // search the parent record's local KEY,
        // value is the current record's foreign KEY

        HttpEntity entity = new NStringEntity(json, ContentType.APPLICATION_JSON);

        try {

            Request request = new Request("POST", table.index + "/" +table.type+ "/_update_by_query");
            request.setEntity(entity);
            request.addParameter("conflicts", "proceed");
            request.addParameter("refresh", "false");
            request.addParameter("wait_for_completion", "false");

            dbFactory
                    .getEs()
                    .getRestClient()
                    .performRequest(request);

        } catch (IOException e)
        {
            logger.error(e.getMessage(), e);
        }
    }

    public void execute(List<AbstractAction> actionList)
    {
        for (AbstractAction action: actionList
        ) {
            action.execute(this);
        }
    }

    public void executeBulk(List<AbstractAction> actionList)
    {
        long nanoTime = System.nanoTime();

        BulkRequest request = new BulkRequest();
        request.setRefreshPolicy(WriteRequest.RefreshPolicy.NONE);

        try {

            for (AbstractAction action : actionList
            ) {
                action.execute(this);
                request.add(
                        ((AbstractRecordAction)action).getRequest(dbFactory)
                );
            }

        } catch (Exception e)
        {
            logger.error(e.getMessage(), e);
            return;
        }

        try {

            BulkResponse bulkResponse = dbFactory.getEs().getClient().bulk(request, RequestOptions.DEFAULT);

            for (BulkItemResponse response : bulkResponse
            ) {
                if (response.isFailed())
                    logger.error(response.getFailureMessage());
            }

        } catch (IOException e) {
            logger.error("Elasticsearch request failed.", e);
        }

        logger.info("Sync {} records, use {} ms", actionList.size(), (System.nanoTime() - nanoTime) / 1000_000.0);
    }
}
