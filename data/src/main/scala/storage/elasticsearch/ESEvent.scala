package io.prediction.data.storage.elasticsearch

import io.prediction.data.storage.Event
import io.prediction.data.storage.StorageError
import io.prediction.data.storage.Events
import io.prediction.data.storage.EventJson4sSupport

import grizzled.slf4j.Logging

import org.elasticsearch.ElasticsearchException
import org.elasticsearch.client.Client
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.node.NodeBuilder.nodeBuilder
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.transport.ConnectTransportException
import org.elasticsearch.action.index.IndexResponse
import org.elasticsearch.action.get.GetResponse
import org.elasticsearch.action.delete.DeleteResponse
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.action.deletebyquery.DeleteByQueryResponse
import org.elasticsearch.action.ActionListener
import org.elasticsearch.index.query.FilterBuilders
import org.elasticsearch.index.query.QueryBuilders

import org.json4s.DefaultFormats
import org.json4s.native.Serialization.{ read, write }
//import org.json4s.ext.JodaTimeSerializers

import scala.util.Try
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.ExecutionContext.Implicits.global // TODO

class ESEvents(client: Client, index: String) extends Events with Logging {

  implicit val formats = DefaultFormats + new EventJson4sSupport.DBSerializer
  //implicit val formats = DefaultFormats.lossless ++ JodaTimeSerializers.all

  val typeName = "events"

  override
  def futureInsert(event: Event): Future[Either[StorageError, String]] = {
    val response = Promise[IndexResponse]

    client.prepareIndex(index, typeName)
      .setSource(write(event))
      .execute(new ESActionListener(response))

    response.future
      .map(r => Right(r.getId()))
      .recover {
        case e: Exception => Left(StorageError(e.toString))
      }
  }

  override
  def futureGet(eventId: String):
    Future[Either[StorageError, Option[Event]]] = {

    val response = Promise[GetResponse]

    client.prepareGet(index, typeName, eventId)
      .execute(new ESActionListener(response))

    response.future
      .map { r =>
        if (r.isExists)
          Right(Some(read[Event](r.getSourceAsString)))
        else
          Right(None)
      }.recover {
        case e: Exception => Left(StorageError(e.toString))
      }
  }

  override
  def futureDelete(eventId: String): Future[Either[StorageError, Boolean]] = {
    val response = Promise[DeleteResponse]

    client.prepareDelete(index, typeName, eventId)
      .execute(new ESActionListener(response))

    response.future
      .map(r => Right(r.isFound()))
      .recover {
        case e: Exception => Left(StorageError(e.toString))
      }
  }

  override
  def futureGetByAppId(appId: Int):
    Future[Either[StorageError, Iterator[Event]]] = {
    val response = Promise[SearchResponse]

    client.prepareSearch(index).setTypes(typeName)
      .setPostFilter(FilterBuilders.termFilter("appId", appId))
      .execute(new ESActionListener(response))

    response.future
      .map{ r =>
        val dataIt = r.getHits().hits()
          .map(h => read[Event](h.getSourceAsString)).toIterator
        Right(dataIt)
      }.recover {
        case e: Exception => Left(StorageError(e.toString))
      }

  }

  override
  def futureDeleteByAppId(appId: Int):
    Future[Either[StorageError, Unit]] = {

    val response = Promise[DeleteByQueryResponse]

    client.prepareDeleteByQuery(index).setTypes(typeName)
      .setQuery(QueryBuilders.termQuery("appId", appId))
      .execute(new ESActionListener(response))

    response.future
      .map { r =>
        val indexResponse = r.getIndex(index)
        val numFailures = indexResponse.getFailedShards()
        if (numFailures != 0)
          Left(StorageError(s"Failed to delete ${numFailures} shards."))
        else
          Right(())
      }.recover {
        case e: Exception => Left(StorageError(e.toString))
      }

  }

}


class ESActionListener[T](val p: Promise[T]) extends ActionListener[T]{
  override def onResponse(r: T) = {
    p.success(r)
  }
  override def onFailure(e: Throwable) = {
    p.failure(e)
  }
}
