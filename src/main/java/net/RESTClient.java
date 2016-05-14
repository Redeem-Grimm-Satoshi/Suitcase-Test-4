package net;

import model.AggregateInfo;
import model.CsvConfig;
import model.ODKCsv;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONException;
import org.apache.wink.json4j.JSONObject;
import org.opendatakit.aggregate.odktables.rest.RFC4180CsvWriter;
import org.opendatakit.wink.client.WinkClient;
import utils.FileUtils;

import javax.swing.*;
import java.io.FileWriter;
import java.io.IOException;

import static org.opendatakit.wink.client.WinkClient.*;

/**
 * Handles most communication to OdkWinkClient
 * <p>
 * Created by Kamil Kalfas
 * kkalfas@soldevelo.com
 * Date: 5/19/15
 * Time: 11:27 AM
 */
public class RESTClient {
  private static final int FETCH_LIMIT = 1000;
  private static final int DELETE_TABLE_DEF_WAIT = 1000;
  private static final int PUSH_DONE_WAIT = 5000;

  private JProgressBar pb;
  private final WinkClient odkWinkClient;
  private final AggregateInfo aggregateInfo;

  //!!!ATTENTION!!! One per table
  public RESTClient(AggregateInfo aggregateInfo) throws Exception {
    this.aggregateInfo = aggregateInfo;
    this.odkWinkClient = new WinkClient();
    this.pb = null;

    odkWinkClient.init(this.aggregateInfo.getHostUrl(), this.aggregateInfo.getUserName(),
        this.aggregateInfo.getPassword());

    updateTableList();
  }

  /**
   * Retrieve formatted rows from ODKCsv and write to file
   *
   * @throws IOException
   * @throws JSONException
   */
  public void writeCSVToFile(CsvConfig config, String dirPath)
      throws IOException, JSONException {
    AttachmentManager attMngr = new AttachmentManager(aggregateInfo, odkWinkClient, dirPath);
    ODKCsv Csv = new ODKCsv(attMngr, this.aggregateInfo);

    if (Csv.getSize() == 0) {
      //Download json if not downloaded
      retrieveRows(Csv);
    }

    pbSetValue(null, "Processing and writing data", false);

    RFC4180CsvWriter csvWriter = new RFC4180CsvWriter(new FileWriter(
        FileUtils.getCSVPath(aggregateInfo, config, dirPath)
            .toAbsolutePath().toString()));

    ODKCsv.ODKCSVIterator csvIt = Csv.getODKCSVIterator();

    //Write header and rows
    csvWriter.writeNext(Csv.getHeader(config));
    while (csvIt.hasNext()) {
      csvWriter.writeNext(csvIt.next(config));

      //Set value of progress bar with percentage of rows done
      pbSetValue((int) ((double) csvIt.getIndex() / Csv.getSize() * this.pb.getMaximum()),
          null, null);
    }

    csvWriter.close();
  }

  public void pushAllData(String dataPath, String version) throws Exception {
    pbSetValue(null, "Uploading...", null);

    odkWinkClient.pushAllDataToUri(
        aggregateInfo.getServerUrl(), aggregateInfo.getAppId(), dataPath, version);
    Thread.sleep(PUSH_DONE_WAIT);
  }

  public void deleteAllRemote(String version) throws Exception {
    pbSetValue(null, "Deleting...", null);

    // Delete all files on the server
    JSONArray files = odkWinkClient.getManifestForAppLevelFiles(
        aggregateInfo.getServerUrl(), aggregateInfo.getAppId(), version
    ).getJSONArray("files");

    for (int j = 0; j < files.size(); j++) {
      odkWinkClient.deleteFile(
          aggregateInfo.getServerUrl(), aggregateInfo.getAppId(),
          files.getJSONObject(j).getString("filename"), version
      );
    }

    // delete table definitions
    for (String id : aggregateInfo.getAllTableId()) {
      int counter = 0; //TODO: remove
      while (
          odkWinkClient.deleteTableDefinition(aggregateInfo.getServerUrl(), aggregateInfo.getAppId()
          , id, aggregateInfo.getSchemaETag(id)) == 500) {
        counter++;
        Thread.sleep(DELETE_TABLE_DEF_WAIT);
      }
      System.out.println("retry count " + counter); //TODO: remove
    }

    Thread.sleep(PUSH_DONE_WAIT);
  }

  public void updateTableList() throws Exception {
    JSONArray tables = odkWinkClient.getTables(aggregateInfo.getServerUrl(), aggregateInfo.getAppId())
        .getJSONArray(jsonTables);

    for (int i = 0; i < tables.size(); i++) {
      String tableId = tables.getJSONObject(i).getString(jsonTableId);
      String eTag = tables.getJSONObject(i).getString(jsonSchemaETag);
      aggregateInfo.addTableId(tableId, eTag);
    }
  }

  /**
   * Set a JProgressBar
   *
   * @param pb JProgressBar
   */
  public void setProgressBar(JProgressBar pb) {
    this.pb = pb;
  }

  /**
   * Download JSON of rows using WinkClient
   *
   * @throws JSONException
   */
  private void retrieveRows(ODKCsv Csv) throws JSONException {
    this.pb.setString("Retrieving rows");

    String cursor = null;
    JSONObject rows;

    do {
      rows = this.odkWinkClient.getRows(
          this.aggregateInfo.getServerUrl(), this.aggregateInfo.getAppId(),
          this.aggregateInfo.getCurrentTableId(),
          this.aggregateInfo.getSchemaETag(this.aggregateInfo.getCurrentTableId()), cursor,
          Integer.toString(RESTClient.FETCH_LIMIT)
      );

      cursor = rows.optString(jsonWebSafeResumeCursor);
      Csv.tryAdd(rows.getJSONArray(jsonRowsString));
    } while (rows.getBoolean(jsonHasMoreResults));
  }

  /**
   * Wrapper for some JProgressBar setters to better handle GUI/CLI hybrid
   *
   * @param value
   * @param status
   * @param isIndeterminate
   */
  private void pbSetValue(Integer value, String status, Boolean isIndeterminate) {
    if (this.pb == null)
      return;

    if (value != null) {
      this.pb.setValue(value);
    }
    if (status != null) {
      this.pb.setString(status);
    }
    if (isIndeterminate != null) {
      this.pb.setIndeterminate(isIndeterminate);
    }
  }
}