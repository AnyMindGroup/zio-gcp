package com.anymindgroup.pubsub

import zio.*
import zio.logging.consoleJsonLogger

trait ZIOPubsubTestApp extends ZIOApp:
  type Environment = TestRunConfig & PubsubConnectionConfig & TopicName & SubscriptionName & Subscriber &
    Publisher[Any, String]

  val environmentTag: EnvironmentTag[Environment] = EnvironmentTag[Environment]

  private val testTopic =
    ZLayer.fromFunction((c: TestRunConfig) => TopicName(c.projectId, s"zio_pubsub_test_${c.label}_${c.testId}"))
  private val testSub =
    ZLayer.fromFunction((c: TestRunConfig) => SubscriptionName(c.projectId, s"zio_pubsub_test_${c.label}_${c.testId}"))

  private val connectionLayer = ZLayer.fromZIO:
    ZIO.systemWith:
      _.env("PUBSUB_EMULATOR").flatMap:
        case Some(v) =>
          v.split(':') match
            case Array(h, p, _*) => ZIO.attempt(PubsubConnectionConfig.Emulator(h, p.toInt))
            case _               => ZIO.dieMessage(s"Invalid pubsub emulator host:port config $v")
        case None => ZIO.succeed(PubsubConnectionConfig.Cloud)

  private def logFormat =
    "%label{timestamp}{%timestamp{yyyy-MM-dd'T'HH:mm:ssZ}}"
      + " %label{severity}{%level}"
      + " %label{fiberId}{%fiberId}"
      + " %label{message}{%message}"
      + " %label{cause}{%cause}"
      + " %label{logger}{%name}"
      + " %kvs"

  private def configProvider: ConfigProvider = ConfigProvider.fromMap(
    Map(
      "logger/format"           -> logFormat,
      "logger/filter/rootLevel" -> LogLevel.Info.label,
    ),
    "/",
  )

  def testBootstrap: ZLayer[
    PubsubConnectionConfig & TopicName & SubscriptionName & TestRunConfig,
    Throwable,
    Subscriber & Publisher[Any, String],
  ]

  protected def setRuntime: ZLayer[Any, Throwable, Unit]

  final def bootstrap =
    setRuntime >>>
      ((Runtime.removeDefaultLoggers >>> Runtime.setConfigProvider(configProvider) >>> consoleJsonLogger()) >>>
        ((connectionLayer >+> TestRunConfig.layer) >+>
          ((testTopic ++ testSub) >+> testBootstrap)))

  final def run =
    (for
      sysProps      <- ZIO.systemWith(_.properties)
      processors     = java.lang.Runtime.getRuntime().availableProcessors()
      config        <- ZIO.service[TestRunConfig]
      s             <- ZIO.service[Subscriber]
      p             <- ZIO.service[Publisher[Any, String]]
      testSub       <- ZIO.service[SubscriptionName]
      messagesAmount = config.messagesAmount
      _             <-
        ZIO.logInfo(
          s"Starting test with $messagesAmount messages and $processors processors and batchSize: ${config.batchSize}"
        ) @@ ZIOAspect.annotated(
          sysProps.toSeq*
        )
      _ <-
        ZIO
          .foreachParDiscard(
            (0 to messagesAmount).map { i =>
              PublishMessage(
                data = testPayload(i.toString()),
                orderingKey = None,
                attributes = testAttributes,
              )
            }.grouped(config.batchSize).collect { case x +: xs => NonEmptyChunk.fromIterable(x, xs) }.toList
          )(msgs => p.publish(msgs))
          .timed
          .flatMap((d, _) =>
            ZIO
              .logInfo(s"Published $messagesAmount messages in ${d.toMillis()}ms")
          )
      _ <-
        s
          .subscribeRaw(testSub)
          .take(messagesAmount)
          .chunks
          .mapZIO(chunk => ZIO.foreachParDiscard(chunk)((_, ack) => ack.ack()))
          .runDrain
          .timed
          .flatMap((d, _) => ZIO.logInfo(s"Consumed in ${d.toMillis}ms"))
    yield ()).timed.flatMap((d, _) => ZIO.logInfo(s"Test completed in ${d.toMillis}ms"))

  private def testPayload(id: String) =
    s"""|{
        |  "id": $id,
        |  "admin_graphql_api_id": "gid://shopify/Order/820982911946154508",
        |  "app_id": null,
        |  "browser_ip": null,
        |  "buyer_accepts_marketing": true,
        |  "cancel_reason": "customer",
        |  "cancelled_at": "2021-12-31T19:00:00-05:00",
        |  "cart_token": null,
        |  "checkout_id": null,
        |  "checkout_token": null,
        |  "client_details": null,
        |  "closed_at": null,
        |  "confirmation_number": null,
        |  "confirmed": false,
        |  "contact_email": "jon@example.com",
        |  "created_at": "2021-12-31T19:00:00-05:00",
        |  "currency": "USD",
        |  "current_shipping_price_set": {
        |    "shop_money": {
        |      "amount": "0.00",
        |      "currency_code": "USD"
        |    },
        |    "presentment_money": {
        |      "amount": "0.00",
        |      "currency_code": "USD"
        |    }
        |  },
        |  "current_subtotal_price": "369.97",
        |  "current_subtotal_price_set": {
        |    "shop_money": {
        |      "amount": "369.97",
        |      "currency_code": "USD"
        |    },
        |    "presentment_money": {
        |      "amount": "369.97",
        |      "currency_code": "USD"
        |    }
        |  },
        |  "current_total_additional_fees_set": null,
        |  "current_total_discounts": "0.00",
        |  "current_total_discounts_set": {
        |    "shop_money": {
        |      "amount": "0.00",
        |      "currency_code": "USD"
        |    },
        |    "presentment_money": {
        |      "amount": "0.00",
        |      "currency_code": "USD"
        |    }
        |  },
        |  "current_total_duties_set": null,
        |  "current_total_price": "369.97",
        |  "current_total_price_set": {
        |    "shop_money": {
        |      "amount": "369.97",
        |      "currency_code": "USD"
        |    },
        |    "presentment_money": {
        |      "amount": "369.97",
        |      "currency_code": "USD"
        |    }
        |  },
        |  "current_total_tax": "0.00",
        |  "current_total_tax_set": {
        |    "shop_money": {
        |      "amount": "0.00",
        |      "currency_code": "USD"
        |    },
        |    "presentment_money": {
        |      "amount": "0.00",
        |      "currency_code": "USD"
        |    }
        |  },
        |  "customer_locale": "en",
        |  "device_id": null,
        |  "discount_codes": [],
        |  "duties_included": false,
        |  "email": "jon@example.com",
        |  "estimated_taxes": false,
        |  "financial_status": "voided",
        |  "fulfillment_status": null,
        |  "landing_site": null,
        |  "landing_site_ref": null,
        |  "location_id": null,
        |  "merchant_business_entity_id": "MTU0ODM4MDAwOQ",
        |  "merchant_of_record_app_id": null,
        |  "name": "#9999",
        |  "note": null,
        |  "note_attributes": [],
        |  "number": 234,
        |  "order_number": 1234,
        |  "order_status_url": "https://jsmith.myshopify.com/548380009/orders/123456abcd/authenticate?key=abcdefg",
        |  "original_total_additional_fees_set": null,
        |  "original_total_duties_set": null,
        |  "payment_gateway_names": [
        |    "visa",
        |    "bogus"
        |  ],
        |  "phone": null,
        |  "po_number": null,
        |  "presentment_currency": "USD",
        |  "processed_at": "2021-12-31T19:00:00-05:00",
        |  "reference": null,
        |  "referring_site": null,
        |  "source_identifier": null,
        |  "source_name": "web",
        |  "source_url": null,
        |  "subtotal_price": "359.97",
        |  "subtotal_price_set": {
        |    "shop_money": {
        |      "amount": "359.97",
        |      "currency_code": "USD"
        |    },
        |    "presentment_money": {
        |      "amount": "359.97",
        |      "currency_code": "USD"
        |    }
        |  },
        |  "tags": "tag1, tag2",
        |  "tax_exempt": false,
        |  "tax_lines": [],
        |  "taxes_included": false,
        |  "test": true,
        |  "token": "123456abcd",
        |  "total_cash_rounding_payment_adjustment_set": {
        |    "shop_money": {
        |      "amount": "0.00",
        |      "currency_code": "USD"
        |    },
        |    "presentment_money": {
        |      "amount": "0.00",
        |      "currency_code": "USD"
        |    }
        |  },
        |  "total_cash_rounding_refund_adjustment_set": {
        |    "shop_money": {
        |      "amount": "0.00",
        |      "currency_code": "USD"
        |    },
        |    "presentment_money": {
        |      "amount": "0.00",
        |      "currency_code": "USD"
        |    }
        |  },
        |  "total_discounts": "20.00",
        |  "total_discounts_set": {
        |    "shop_money": {
        |      "amount": "20.00",
        |      "currency_code": "USD"
        |    },
        |    "presentment_money": {
        |      "amount": "20.00",
        |      "currency_code": "USD"
        |    }
        |  },
        |  "total_line_items_price": "369.97",
        |  "total_line_items_price_set": {
        |    "shop_money": {
        |      "amount": "369.97",
        |      "currency_code": "USD"
        |    },
        |    "presentment_money": {
        |      "amount": "369.97",
        |      "currency_code": "USD"
        |    }
        |  },
        |  "total_outstanding": "369.97",
        |  "total_price": "359.97",
        |  "total_price_set": {
        |    "shop_money": {
        |      "amount": "359.97",
        |      "currency_code": "USD"
        |    },
        |    "presentment_money": {
        |      "amount": "359.97",
        |      "currency_code": "USD"
        |    }
        |  },
        |  "total_shipping_price_set": {
        |    "shop_money": {
        |      "amount": "10.00",
        |      "currency_code": "USD"
        |    },
        |    "presentment_money": {
        |      "amount": "10.00",
        |      "currency_code": "USD"
        |    }
        |  },
        |  "total_tax": "0.00",
        |  "total_tax_set": {
        |    "shop_money": {
        |      "amount": "0.00",
        |      "currency_code": "USD"
        |    },
        |    "presentment_money": {
        |      "amount": "0.00",
        |      "currency_code": "USD"
        |    }
        |  },
        |  "total_tip_received": "0.00",
        |  "total_weight": 0,
        |  "updated_at": "2021-12-31T19:00:00-05:00",
        |  "user_id": null,
        |  "billing_address": {
        |    "first_name": "Steve",
        |    "address1": "123 Shipping Street",
        |    "phone": "555-555-SHIP",
        |    "city": "Shippington",
        |    "zip": "40003",
        |    "province": "Kentucky",
        |    "country": "United States",
        |    "last_name": "Shipper",
        |    "address2": null,
        |    "company": "Shipping Company",
        |    "latitude": null,
        |    "longitude": null,
        |    "name": "Steve Shipper",
        |    "country_code": "US",
        |    "province_code": "KY"
        |  },
        |  "customer": {
        |    "id": 115310627314723954,
        |    "created_at": null,
        |    "updated_at": null,
        |    "first_name": "John",
        |    "last_name": "Smith",
        |    "state": "disabled",
        |    "note": null,
        |    "verified_email": true,
        |    "multipass_identifier": null,
        |    "tax_exempt": false,
        |    "email": "john@example.com",
        |    "phone": null,
        |    "currency": "USD",
        |    "tax_exemptions": [],
        |    "admin_graphql_api_id": "gid://shopify/Customer/115310627314723954",
        |    "default_address": {
        |      "id": 715243470612851245,
        |      "customer_id": 115310627314723954,
        |      "first_name": "John",
        |      "last_name": "Smith",
        |      "company": null,
        |      "address1": "123 Elm St.",
        |      "address2": null,
        |      "city": "Ottawa",
        |      "province": "Ontario",
        |      "country": "Canada",
        |      "zip": "K2H7A8",
        |      "phone": "123-123-1234",
        |      "name": "John Smith",
        |      "province_code": "ON",
        |      "country_code": "CA",
        |      "country_name": "Canada",
        |      "default": true
        |    }
        |  },
        |  "discount_applications": [],
        |  "fulfillments": [],
        |  "line_items": [
        |    {
        |      "id": 487817672276298554,
        |      "admin_graphql_api_id": "gid://shopify/LineItem/487817672276298554",
        |      "attributed_staffs": [
        |        {
        |          "id": "gid://shopify/StaffMember/902541635",
        |          "quantity": 1
        |        }
        |      ],
        |      "current_quantity": 1,
        |      "fulfillable_quantity": 1,
        |      "fulfillment_service": "manual",
        |      "fulfillment_status": null,
        |      "gift_card": false,
        |      "grams": 100,
        |      "name": "Aviator sunglasses",
        |      "price": "89.99",
        |      "price_set": {
        |        "shop_money": {
        |          "amount": "89.99",
        |          "currency_code": "USD"
        |        },
        |        "presentment_money": {
        |          "amount": "89.99",
        |          "currency_code": "USD"
        |        }
        |      },
        |      "product_exists": true,
        |      "product_id": 788032119674292922,
        |      "properties": [],
        |      "quantity": 1,
        |      "requires_shipping": true,
        |      "sales_line_item_group_id": null,
        |      "sku": "SKU2006-001",
        |      "taxable": true,
        |      "title": "Aviator sunglasses",
        |      "total_discount": "0.00",
        |      "total_discount_set": {
        |        "shop_money": {
        |          "amount": "0.00",
        |          "currency_code": "USD"
        |        },
        |        "presentment_money": {
        |          "amount": "0.00",
        |          "currency_code": "USD"
        |        }
        |      },
        |      "variant_id": null,
        |      "variant_inventory_management": null,
        |      "variant_title": null,
        |      "vendor": null,
        |      "tax_lines": [],
        |      "duties": [],
        |      "discount_allocations": []
        |    },
        |    {
        |      "id": 976318377106520349,
        |      "admin_graphql_api_id": "gid://shopify/LineItem/976318377106520349",
        |      "attributed_staffs": [],
        |      "current_quantity": 1,
        |      "fulfillable_quantity": 1,
        |      "fulfillment_service": "manual",
        |      "fulfillment_status": null,
        |      "gift_card": false,
        |      "grams": 1000,
        |      "name": "Mid-century lounger",
        |      "price": "159.99",
        |      "price_set": {
        |        "shop_money": {
        |          "amount": "159.99",
        |          "currency_code": "USD"
        |        },
        |        "presentment_money": {
        |          "amount": "159.99",
        |          "currency_code": "USD"
        |        }
        |      },
        |      "product_exists": true,
        |      "product_id": 788032119674292922,
        |      "properties": [],
        |      "quantity": 1,
        |      "requires_shipping": true,
        |      "sales_line_item_group_id": 142831562,
        |      "sku": "SKU2006-020",
        |      "taxable": true,
        |      "title": "Mid-century lounger",
        |      "total_discount": "0.00",
        |      "total_discount_set": {
        |        "shop_money": {
        |          "amount": "0.00",
        |          "currency_code": "USD"
        |        },
        |        "presentment_money": {
        |          "amount": "0.00",
        |          "currency_code": "USD"
        |        }
        |      },
        |      "variant_id": null,
        |      "variant_inventory_management": null,
        |      "variant_title": null,
        |      "vendor": null,
        |      "tax_lines": [],
        |      "duties": [],
        |      "discount_allocations": []
        |    },
        |    {
        |      "id": 315789986012684393,
        |      "admin_graphql_api_id": "gid://shopify/LineItem/315789986012684393",
        |      "attributed_staffs": [],
        |      "current_quantity": 1,
        |      "fulfillable_quantity": 1,
        |      "fulfillment_service": "manual",
        |      "fulfillment_status": null,
        |      "gift_card": false,
        |      "grams": 500,
        |      "name": "Coffee table",
        |      "price": "119.99",
        |      "price_set": {
        |        "shop_money": {
        |          "amount": "119.99",
        |          "currency_code": "USD"
        |        },
        |        "presentment_money": {
        |          "amount": "119.99",
        |          "currency_code": "USD"
        |        }
        |      },
        |      "product_exists": true,
        |      "product_id": 788032119674292922,
        |      "properties": [],
        |      "quantity": 1,
        |      "requires_shipping": true,
        |      "sales_line_item_group_id": 142831562,
        |      "sku": "SKU2006-035",
        |      "taxable": true,
        |      "title": "Coffee table",
        |      "total_discount": "0.00",
        |      "total_discount_set": {
        |        "shop_money": {
        |          "amount": "0.00",
        |          "currency_code": "USD"
        |        },
        |        "presentment_money": {
        |          "amount": "0.00",
        |          "currency_code": "USD"
        |        }
        |      },
        |      "variant_id": null,
        |      "variant_inventory_management": null,
        |      "variant_title": null,
        |      "vendor": null,
        |      "tax_lines": [],
        |      "duties": [],
        |      "discount_allocations": []
        |    }
        |  ],
        |  "payment_terms": null,
        |  "refunds": [],
        |  "shipping_address": {
        |    "first_name": "Steve",
        |    "address1": "123 Shipping Street",
        |    "phone": "555-555-SHIP",
        |    "city": "Shippington",
        |    "zip": "40003",
        |    "province": "Kentucky",
        |    "country": "United States",
        |    "last_name": "Shipper",
        |    "address2": null,
        |    "company": "Shipping Company",
        |    "latitude": null,
        |    "longitude": null,
        |    "name": "Steve Shipper",
        |    "country_code": "US",
        |    "province_code": "KY"
        |  },
        |  "shipping_lines": [
        |    {
        |      "id": 271878346596884015,
        |      "carrier_identifier": null,
        |      "code": null,
        |      "current_discounted_price_set": {
        |        "shop_money": {
        |          "amount": "0.00",
        |          "currency_code": "USD"
        |        },
        |        "presentment_money": {
        |          "amount": "0.00",
        |          "currency_code": "USD"
        |        }
        |      },
        |      "discounted_price": "0.00",
        |      "discounted_price_set": {
        |        "shop_money": {
        |          "amount": "0.00",
        |          "currency_code": "USD"
        |        },
        |        "presentment_money": {
        |          "amount": "0.00",
        |          "currency_code": "USD"
        |        }
        |      },
        |      "is_removed": false,
        |      "phone": null,
        |      "price": "10.00",
        |      "price_set": {
        |        "shop_money": {
        |          "amount": "10.00",
        |          "currency_code": "USD"
        |        },
        |        "presentment_money": {
        |          "amount": "10.00",
        |          "currency_code": "USD"
        |        }
        |      },
        |      "requested_fulfillment_service_id": null,
        |      "source": "shopify",
        |      "title": "Generic Shipping",
        |      "tax_lines": [],
        |      "discount_allocations": []
        |    }
        |  ],
        |  "returns": []
        |}""".stripMargin

  private val testAttributes = Map(
    "X-Shopify-Event-Id"     -> "029964c7-7bef-4cf5-94b6-d2877ea94917",
    "X-Shopify-API-Version"  -> "2024-10",
    "X-Shopify-Triggered-At" -> "2025-10-11T05:27:13.653518304Z",
    "X-Shopify-Shop-Domain"  -> "abc.myshopify.com",
    "Content-Type"           -> "application/json",
    "X-Shopify-Topic"        -> "customers/update",
    "X-Shopify-Webhook-Id"   -> "fe3f8ab5-49d5-4bdc-8bb9-f80cd169408a",
    "X-Shopify-Hmac-SHA256"  -> "WrcmgxoDRt2JnjBH80Ti3g3tKUqwoDM10Q4DaWfURAStAWaAUvTP1Gg5Veg=",
  )
end ZIOPubsubTestApp

case class TestRunConfig(
  messagesAmount: Int,
  projectId: String,
  testId: String,
  label: String,
  batchSize: Int,
)

object TestRunConfig:
  val layer = ZLayer.fromZIO:
    for
      argsMap <- ZIOAppArgs.getArgs.map:
                   _.map(arg =>
                     val (k, v) = arg.splitAt(arg.indexOf("="))
                     (
                       k,
                       v.stripPrefix("=").stripPrefix("\"").stripPrefix("'").stripSuffix("\"").stripSuffix("'"),
                     )
                   ).toMap
      label <- argsMap.get("-label") match
                 case Some(label) => ZIO.succeed(label)
                 case None        => ZIO.dieMessage("Missing -label")
      messagesAmount <- argsMap.get("-messages-amount").flatMap(_.toIntOption) match
                          case Some(conf) => ZIO.succeed(conf)
                          case None       => ZIO.dieMessage("Missing -messages-amount")
      batchSize = argsMap.get("-batch-size").map(_.toInt) match
                    case Some(s) => s
                    case None    => 100
      testId    <- ZIO.succeed(scala.util.Random.alphanumeric.take(10).mkString)
      projectId <- ZIO.serviceWithZIO[PubsubConnectionConfig]:
                     case PubsubConnectionConfig.Cloud =>
                       ZIO.systemWith(_.env("GCP_PROJECT")).someOrFail(Throwable("Missing GCP_PROJECT"))
                     case _: PubsubConnectionConfig.Emulator => ZIO.succeed("any")
    yield TestRunConfig(
      messagesAmount = messagesAmount,
      testId = testId,
      projectId = projectId,
      label = label,
      batchSize = batchSize,
    )
