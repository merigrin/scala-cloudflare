package dwolla.cloudflare

import cats.data._
import cats.effect._
import com.dwolla.cloudflare._
import com.dwolla.cloudflare.domain.dto._
import com.dwolla.cloudflare.domain.dto.dns.DnsRecordDTO
import com.dwolla.cloudflare.domain.model.ZoneSettings.CloudflareSettingValue
import com.dwolla.cloudflare.domain.model._
import io.circe.Json
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.dsl.Http4sDsl
import org.http4s.server.middleware.VirtualHost
import org.http4s.server.middleware.VirtualHost.exact
import org.http4s.util.CaseInsensitiveString

class FakeCloudflareService(authorization: CloudflareAuthorization) extends Http4sDsl[IO] {

  object OptionalPageQueryParamMatcher extends OptionalQueryParamDecoderMatcher[Int]("page")
  object DirectionPageQueryParamMatcher extends QueryParamDecoderMatcher[String]("direction")
  object ListAccessControlRulesParameters {
    object modeParam extends QueryParamDecoderMatcher[String]("mode")
  }

  import com.dwolla.cloudflare.domain.model.Implicits._

  object ListZonesQueryParameters {
    object zoneName extends QueryParamDecoderMatcher[String]("name")
    object status extends QueryParamDecoderMatcher[String]("status")
  }

  object ListRecordsForZoneQueryParameters {
    object recordNameParam extends QueryParamDecoderMatcher[String]("name")
    object contentParam extends OptionalQueryParamDecoderMatcher[String]("content")
    object recordTypeParam extends OptionalQueryParamDecoderMatcher[String]("type")
  }

  def listZones(zoneName: String, responseBody: String) = HttpService[IO] {
    case GET -> Root / "client" / "v4" / "zones" :? ListZonesQueryParameters.zoneName(zone) +& ListZonesQueryParameters.status("active") if zone == zoneName ⇒
      okWithJson(responseBody)
    case GET -> Root / "client" / "v4" / "zones" ⇒
      okWithJson(
        """{
          |  "result": [],
          |  "result_info": {
          |    "page": 1,
          |    "per_page": 20,
          |    "total_pages": 1,
          |    "count": 0,
          |    "total_count": 0
          |  },
          |  "success": false,
          |  "errors": [],
          |  "messages": []
          |}""".stripMargin)
  }

  val failure = okWithJson(
    """{
      |  "result": [],
      |  "result_info": {
      |    "page": 1,
      |    "per_page": 20,
      |    "total_pages": 1,
      |    "count": 0,
      |    "total_count": 0
      |  },
      |  "success": false,
      |  "errors": [{"code": 42, "message": "oops"}],
      |  "messages": []
      |}""".stripMargin)

  def getDnsRecordByUri(fakeZoneId: String, fakeRecordId: String) = HttpService[IO] {
    case req@GET -> Root / "client" / "v4" / "zones" / zoneId / "dns_records" / recordId if zoneId == fakeZoneId && recordId == fakeRecordId ⇒
      val record: DnsRecord = IdentifiedDnsRecord(
        physicalResourceId = shapeless.tag[PhysicalResourceIdTag][String](req.uri.toString()),
        zoneId = shapeless.tag[ZoneIdTag][String](fakeZoneId),
        resourceId = shapeless.tag[ResourceIdTag][String](fakeRecordId),
        name = "example.hydragents.xyz",
        content = "content.hydragents.xyz",
        recordType = "CNAME",
      )

      val responseBody: ResponseDTO[DnsRecordDTO] = ResponseDTO(
        result = record.toDto,
        success = true,
        errors = None,
        messages = None,
      )

      Ok(responseBody.asJson)
    case GET -> Root / "client" / "v4" / "zones" / zoneId / "dns_records" / recordId if zoneId != fakeZoneId || recordId != fakeRecordId ⇒
      BadRequest(ResponseDTO[None.type](
        result = None,
        success = false,
        errors = Option(List(
          ResponseInfoDTO(Option(7003), s"Could not route to /zones/$zoneId/dns_records/$recordId, perhaps your object identifier is invalid?"),
          ResponseInfoDTO(Option(7000), "No route for that URI")
        )),
        messages = None,
      ).asJson)
  }

  def listRecordsForZone(zoneId: String,
    recordName: String,
    responseBody: String,
    contentFilter: Option[String] = None,
    recordTypeFilter: Option[String] = None,
  ) = {
    import ListRecordsForZoneQueryParameters._
    HttpService[IO] {
      case GET -> Root / "client" / "v4" / "zones" / zone / "dns_records" :? recordNameParam(name) +& contentParam(c) +& recordTypeParam(t)
        if zone == zoneId &&
          name == recordName &&
          c == contentFilter &&
          t == recordTypeFilter ⇒
        okWithJson(responseBody)
      case GET -> Root / "client" / "v4" / "zones" / _ ⇒ failure
    }
  }

  def createRecordInZone(zoneId: String) = HttpService[IO] {
    case req@POST -> Root / "client" / "v4" / "zones" / zone / "dns_records" if zone == zoneId ⇒
      for {
        dnsRecordDTO ← req.decodeJson[DnsRecordDTO]
        res ←
          if (dnsRecordDTO.id.isDefined) BadRequest()
          else {
            Created(ResponseDTO[DnsRecordDTO](
              result = dnsRecordDTO.copy(id = Option("fake-record-id"), ttl = dnsRecordDTO.ttl.orElse(Some(1))),
              success = true,
              errors = None,
              messages = None
            ).asJson)
          }
      } yield res
  }

  def createRecordThatAlreadyExists(zoneId: String) = HttpService[IO] {
    case POST -> Root / "client" / "v4" / "zones" / zone / "dns_records" if zone == zoneId ⇒
      BadRequest(ResponseDTO[DnsRecordDTO](
        result = None,
        success = false,
        errors = Option(List(ResponseInfoDTO(code = Option(81057), message = "The record already exists."))),
        messages = None,
      ).asJson)
  }

  def updateRecordInZone(zoneId: String, recordId: String) = HttpService[IO] {
    case req@PUT -> Root / "client" / "v4" / "zones" / zone / "dns_records" / record if zone == zoneId && record == recordId ⇒
      for {
        dnsRecordDTO ← req.decodeJson[DnsRecordDTO]
        res ←
          if (dnsRecordDTO.id.isDefined) BadRequest()
          else {
            Ok(ResponseDTO[DnsRecordDTO](
              result = dnsRecordDTO.copy(id = Option(recordId), ttl = dnsRecordDTO.ttl.orElse(Some(1)), proxied = dnsRecordDTO.proxied.orElse(Some(false))),
              success = true,
              errors = None,
              messages = None
            ).asJson)
          }
      } yield res
  }

  def deleteRecordInZone(zoneId: String,
    recordId: String,
  ) = HttpService[IO] {
    case DELETE -> Root / "client" / "v4" / "zones" / zone / "dns_records" / record if zone == zoneId && record == recordId ⇒
      Ok(ResponseDTO[DeleteResult](
        result = DeleteResult(id = recordId),
        success = true,
        errors = None,
        messages = None
      ).asJson)
  }

  def failedDeleteRecordInZone(zoneId: String, recordId: String, responseBody: String) = HttpService[IO] {
    case DELETE -> Root / "client" / "v4" / "zones" / zone / "dns_records" / record if zone == zoneId && record == recordId ⇒
      BadRequest(parseJson(responseBody))
  }

  def listRateLimits(pages: Map[Int, String], zoneId: String) = HttpService[IO] {
    case GET -> Root / "client" / "v4" / "zones" / zone / "rate_limits" :? OptionalPageQueryParamMatcher(pageQuery) ⇒
      if (zone != zoneId) BadRequest()
      else {
        pages.get(pageQuery.getOrElse(1)).fold(BadRequest()) { pageBody ⇒
          okWithJson(pageBody)
        }
      }
  }

  def rateLimitById(responseBody: String, zoneId: String, rateLimitId: String, status: Status = Status.Ok) = HttpService[IO] {
    case GET -> Root / "client" / "v4" / "zones" / zone / "rate_limits" / rateLimit ⇒
      if (zoneId != zone || rateLimitId != rateLimit) BadRequest("Invalid account id")
      else {
        Response(status).withBody(parseJson(responseBody))
      }
  }

  def createRateLimit(responseBody: String, zoneId: String, status: Status = Status.Ok) = HttpService[IO] {
    case POST -> Root / "client" / "v4" / "zones" / zone / "rate_limits" ⇒
      if (zone != zoneId) BadRequest()
      else {
        Response(status).withBody(parseJson(responseBody))
      }
  }

  def updateRateLimit(responseBody: String, zoneId: String, rateLimitId: String, status: Status = Status.Ok) = HttpService[IO] {
    case PUT -> Root / "client" / "v4" / "zones" / zone / "rate_limits" / rateLimit ⇒
      if (zone != zoneId || rateLimit != rateLimitId) BadRequest()
      else {
        Response(status).withBody(parseJson(responseBody))
      }
  }

  def deleteRateLimit(responseBody: String, zoneId: String, rateLimitId: String, status: Status = Status.Ok) = HttpService[IO] {
    case DELETE -> Root / "client" / "v4" / "zones" / zone / "rate_limits" / rateLimit ⇒
      if (zone != zoneId || rateLimit != rateLimitId) BadRequest()
      else {
        Response(status).withBody(parseJson(responseBody))
      }
  }

  def listChallengeAccessRules(pages: Map[Int, Json], accountId: String) = HttpService[IO] {
    case GET -> Root / "client" / "v4" / "accounts" / account / "firewall" / "access_rules" / "rules" :? OptionalPageQueryParamMatcher(pageQuery) +& ListAccessControlRulesParameters.modeParam("challenge") ⇒
      if (account != accountId) BadRequest()
      else {
        pages.get(pageQuery.getOrElse(1)).fold(BadRequest()) { pageBody ⇒
          Ok(pageBody)
        }
      }
  }

  def listWhitelistAccessRules(pages: Map[Int, Json], accountId: String) = HttpService[IO] {
    case GET -> Root / "client" / "v4" / "accounts" / account / "firewall" / "access_rules" / "rules" :? OptionalPageQueryParamMatcher(pageQuery) +& ListAccessControlRulesParameters.modeParam("whitelist") ⇒
      if (account != accountId) BadRequest()
      else {
        pages.get(pageQuery.getOrElse(1)).fold(BadRequest()) { pageBody ⇒
          Ok(pageBody)
        }
      }
  }

  def createAccessRule(responseBody: Json, accountId: String, status: Status = Status.Ok) = HttpService[IO] {
    case POST -> Root / "client" / "v4" / "accounts" / account / "firewall" / "access_rules" / "rules" ⇒
      if (account != accountId) BadRequest()
      else {
        Response(status).withBody(responseBody)
      }
  }

  def deleteAccessRule(responseBody: Json, accountId: String, ruleId: String, status: Status = Status.Ok) = HttpService[IO] {
    case DELETE -> Root / "client" / "v4" / "accounts" / account / "firewall" / "access_rules" / "rules" / rule ⇒
      if (account != accountId || rule != ruleId) BadRequest()
      else {
        Response(status).withBody(responseBody)
      }
  }

  def listAccounts(pages: Map[Int, String]) = HttpService[IO] {
    case GET -> Root / "client" / "v4" / "accounts" :? OptionalPageQueryParamMatcher(pageQuery) +& DirectionPageQueryParamMatcher(directionQuery) ⇒
      if (directionQuery != "asc") BadRequest()
      else {
        pages.get(pageQuery.getOrElse(1)).fold(BadRequest()) { pageBody ⇒
          okWithJson(pageBody)
        }
      }
  }

  def accountById(responseBody: String, accountId: String, status: Status = Status.Ok) = HttpService[IO] {
    case GET -> Root / "client" / "v4" / "accounts" / account ⇒
      if (account != accountId) BadRequest("Invalid account id")
      else {
        Response(status).withBody(parseJson(responseBody))
      }
  }

  def listAccountRoles(pages: Map[Int, String], accountId: String) = HttpService[IO] {
    case GET -> Root / "client" / "v4" / "accounts" / account / "roles" :? OptionalPageQueryParamMatcher(pageQuery) ⇒
      if (account != accountId) BadRequest()
      else {
        pages.get(pageQuery.getOrElse(1)).fold(BadRequest()) { pageBody ⇒
          okWithJson(pageBody)
        }
      }
  }

  def getAccountMember(responseBody: String, accountId: String, accountMemberId: String, status: Status = Status.Ok) = HttpService[IO] {
    case GET -> Root / "client" / "v4" / "accounts" / account / "members" / accountMember ⇒
      if (account != accountId || accountMember != accountMemberId) BadRequest()
      else {
        Response(status).withBody(parseJson(responseBody))
      }
  }

  def addAccountMember(responseBody: String, accountId: String, status: Status = Status.Ok) = HttpService[IO] {
    case POST -> Root / "client" / "v4" / "accounts" / account / "members" ⇒
      if (account != accountId) BadRequest()
      else {
        Response(status).withBody(parseJson(responseBody))
      }
  }

  def updateAccountMember(responseBody: String, accountId: String, accountMemberId: String, status: Status = Status.Ok) = HttpService[IO] {
    case PUT -> Root / "client" / "v4" / "accounts" / account / "members" / accountMember ⇒
      if (account != accountId || accountMember != accountMemberId) BadRequest()
      else {
        Response(status).withBody(parseJson(responseBody))
      }
  }

  def removeAccountMember(responseBody: String, accountId: String, accountMemberId: String, status: Status = Status.Ok) = HttpService[IO] {
    case DELETE -> Root / "client" / "v4" / "accounts" / account / "members" / accountMember ⇒
      if (account != accountId || accountMember != accountMemberId) BadRequest()
      else {
        Response(status).withBody(parseJson(responseBody))
      }
  }

  def setTlsLevelService(zoneId: String, expectedValue: String) = HttpService[IO] {
    case req@PATCH -> Root / "client" / "v4" / "zones" / id / "settings" / "ssl" if id == zoneId ⇒
      for {
        input ← req.decodeJson[CloudflareSettingValue]
        _ ← if (input.value != expectedValue) IO.raiseError(new RuntimeException(s"Expected $expectedValue but got ${input.value}")) else IO.unit
        res ← okWithJson(
          """{
            |  "success": true,
            |  "errors": [],
            |  "messages": [],
            |  "result": {
            |    "id": "ssl",
            |    "value": "full",
            |    "editable": true,
            |    "modified_on": "2014-01-01T05:20:00.12345Z"
            |  }
            |}
          """.stripMargin)
      } yield res
  }

  def setSecurityLevelService(zoneId: String, expectedValue: String) = HttpService[IO] {
    case req@PATCH -> Root / "client" / "v4" / "zones" / id / "settings" / "security_level" if id == zoneId ⇒
      for {
        input ← req.decodeJson[CloudflareSettingValue]
        _ ← if (input.value != expectedValue) IO.raiseError(new RuntimeException(s"Expected $expectedValue but got ${input.value}")) else IO.unit
        res ← okWithJson(
          """{
            |  "success": true,
            |  "errors": [],
            |  "messages": [],
            |  "result": {
            |    "id": "security_level",
            |    "value": "high",
            |    "editable": true,
            |    "modified_on": "2014-01-01T05:20:00.12345Z"
            |  }
            |}
          """.stripMargin)
      } yield res
  }

  def listLogpushJobs(zoneId: String, responseBody: Json) = HttpService[IO] {
    case GET -> Root / "client" / "v4" / "zones" / id / "logpush" / "jobs" if id == zoneId ⇒
      Ok(responseBody)
  }

  def createLogpushOwnership(zoneId: String, responseBody: Json) = HttpService[IO] {
    case POST -> Root / "client" / "v4" / "zones" / id / "logpush" / "ownership" if id == zoneId ⇒
      Ok(responseBody)
  }

  def createLogpushJob(zoneId: String, responseBody: Json) = HttpService[IO] {
    case POST -> Root / "client" / "v4" / "zones" / id / "logpush" / "jobs" if id == zoneId ⇒
      Ok(responseBody)
  }

  private def okWithJson(responseBody: String) = {
    Ok(parseJson(responseBody))
  }

  private def parseJson(responseBody: String): Json = {
    parse(responseBody) match {
      case Left(ex) ⇒ throw ex
      case Right(x) ⇒ x
    }
  }

  def cloudflareApi(service: HttpService[IO]) = Kleisli[OptionT[IO, ?], Request[IO], Response[IO]] { req ⇒
    req.headers.get(CaseInsensitiveString("X-Auth-Email")) match {
      case Some(Header(_, email)) if email == authorization.email ⇒
        req.headers.get(CaseInsensitiveString("X-Auth-Key")) match {
          case Some(Header(_, key)) if key == authorization.key ⇒
            VirtualHost(exact(service, "api.cloudflare.com")).run(req)
          case _ ⇒ OptionT.liftF(Forbidden())
        }
      case _ ⇒ OptionT.liftF(Forbidden())
    }
  }

  def client(service: HttpService[IO]) = Client.fromHttpService(cloudflareApi(service))
}
