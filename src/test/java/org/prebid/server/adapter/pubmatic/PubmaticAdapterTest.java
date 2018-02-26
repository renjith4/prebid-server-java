package org.prebid.server.adapter.pubmatic;

import com.iab.openrtb.request.App;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Device;
import com.iab.openrtb.request.Format;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Publisher;
import com.iab.openrtb.request.Site;
import com.iab.openrtb.request.Source;
import com.iab.openrtb.request.User;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.prebid.server.VertxTest;
import org.prebid.server.adapter.model.ExchangeCall;
import org.prebid.server.adapter.model.HttpRequest;
import org.prebid.server.adapter.pubmatic.model.PubmaticParams;
import org.prebid.server.auction.model.AdUnitBid;
import org.prebid.server.auction.model.AdapterRequest;
import org.prebid.server.auction.model.PreBidRequestContext;
import org.prebid.server.cookie.UidsCookie;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.proto.request.PreBidRequest;
import org.prebid.server.proto.request.Video;
import org.prebid.server.proto.response.BidderDebug;
import org.prebid.server.proto.response.MediaType;
import org.prebid.server.usersyncer.PubmaticUsersyncer;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.Collections.*;
import static java.util.function.Function.identity;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

public class PubmaticAdapterTest extends VertxTest {

    private static final String ADAPTER = "pubmatic";
    private static final String ENDPOINT_URL = "http://endpoint.org/";
    private static final String USERSYNC_URL = "//usersync.org/";
    private static final String EXTERNAL_URL = "http://external.org/";

    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private UidsCookie uidsCookie;

    private AdapterRequest adapterRequest;
    private PreBidRequestContext preBidRequestContext;
    private ExchangeCall exchangeCall;
    private PubmaticAdapter adapter;
    private PubmaticUsersyncer usersyncer;

    @Before
    public void setUp() {
        adapterRequest = givenBidderCustomizable(identity());
        preBidRequestContext = givenPreBidRequestContextCustomizable(identity(), identity());
        usersyncer = new PubmaticUsersyncer(USERSYNC_URL, EXTERNAL_URL);
        adapter = new PubmaticAdapter(usersyncer, ENDPOINT_URL);
    }

    @Test
    public void creationShouldFailOnNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> new PubmaticAdapter(null, null));
        assertThatNullPointerException().isThrownBy(() -> new PubmaticAdapter(usersyncer, null));
    }

    @Test
    public void creationShouldFailOnInvalidEndpointUrl() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PubmaticAdapter(usersyncer, "invalid_url"))
                .withMessage("URL supplied is not valid: invalid_url");
    }

    @Test
    public void makeHttpRequestsShouldReturnRequestsWithExpectedHeaders() {
        // when
        final List<HttpRequest> httpRequests = adapter.makeHttpRequests(adapterRequest, preBidRequestContext);

        // then
        assertThat(httpRequests).flatExtracting(r -> r.getHeaders().entries())
                .extracting(Map.Entry::getKey, Map.Entry::getValue)
                .containsOnly(tuple("Content-Type", "application/json;charset=utf-8"),
                        tuple("Accept", "application/json"),
                        tuple("Set-Cookie", "KADUSERCOOKIE="));
    }

    @Test
    public void makeHttpRequestsShouldFailIfNoAtLeastOneValidAdunitBidParams() {
        // given
        adapterRequest = AdapterRequest.of(ADAPTER, singletonList(
                givenAdUnitBidCustomizable(builder -> builder.params(null))));

        // when and then
        assertThatThrownBy(() -> adapter.makeHttpRequests(adapterRequest, preBidRequestContext))
                .isExactlyInstanceOf(PreBidException.class)
                .hasMessage("Incorrect adSlot / Publisher param");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void requestBidsShouldSendBidRequestWithNotModifiedImpIfInvalidParams() {
        // given
        adapterRequest = AdapterRequest.of(ADAPTER, asList(
                givenAdUnitBidCustomizable(identity()),
                givenAdUnitBidCustomizable(builder -> builder.params(mapper.valueToTree(
                        PubmaticParams.of(null, null)))),
                givenAdUnitBidCustomizable(builder -> builder.params(mapper.valueToTree(
                        PubmaticParams.of("publisherID", null)))),
                givenAdUnitBidCustomizable(builder -> builder.params(mapper.valueToTree(
                        PubmaticParams.of("publisherID", "slot42")))),
                givenAdUnitBidCustomizable(builder -> builder.params(mapper.valueToTree(
                        PubmaticParams.of("publisherID", "slot42@200")))),
                givenAdUnitBidCustomizable(builder -> builder.params(mapper.valueToTree(
                        PubmaticParams.of("publisherID", "slot42@200xNonNumber"))))));

        given(uidsCookie.uidFrom(eq(ADAPTER))).willReturn("buyerUid");

        // when
        final List<HttpRequest> httpRequests = adapter.makeHttpRequests(adapterRequest, preBidRequestContext);

        // then
        assertThat(httpRequests).flatExtracting(r -> r.getBidRequest().getImp()).hasSize(6);
        assertThat(httpRequests).flatExtracting(r -> r.getBidRequest().getImp())
                .extracting(Imp::getTagid)
                .containsOnly("slot1", null, null, null, null, null);

        final List formats = singletonList(Format.builder().w(480).h(320).build());
        assertThat(httpRequests).flatExtracting(r -> r.getBidRequest().getImp())
                .extracting(Imp::getBanner).extracting(Banner::getFormat)
                .containsOnly(null, formats, formats, formats, formats, formats);

        assertThat(httpRequests).flatExtracting(r -> r.getBidRequest().getImp())
                .extracting(Imp::getBanner).extracting(Banner::getW)
                .containsOnly(300, 480, 480, 480, 480, 480);

        assertThat(httpRequests).flatExtracting(r -> r.getBidRequest().getImp())
                .extracting(Imp::getBanner).extracting(Banner::getH)
                .containsOnly(250, 320, 320, 320, 320, 320);
    }

    @Test
    public void requestBidsShouldTolerateAdSlotExtraSuffix() {
        // given
        adapterRequest = givenBidderCustomizable(
                builder -> builder
                        .params(mapper.valueToTree(PubmaticParams.of("publisherID", "slot42@200x150:zzz"))));

        given(uidsCookie.uidFrom(eq(ADAPTER))).willReturn("buyerUid");

        // when
        final List<HttpRequest> httpRequests = adapter.makeHttpRequests(adapterRequest, preBidRequestContext);

        // then
        assertThat(httpRequests).isNotEmpty();
    }

    @Test
    public void makeHttpRequestsShouldFailIfMediaTypeIsEmpty() {
        //given
        adapterRequest = AdapterRequest.of(ADAPTER, singletonList(
                givenAdUnitBidCustomizable(builder -> builder
                        .adUnitCode("adUnitCode1")
                        .mediaTypes(emptySet()))));

        preBidRequestContext = givenPreBidRequestContextCustomizable(identity(), identity());

        // when and then
        assertThatThrownBy(() -> adapter.makeHttpRequests(adapterRequest, preBidRequestContext))
                .isExactlyInstanceOf(PreBidException.class)
                .hasMessage("openRTB bids need at least one Imp");
    }

    @Test
    public void makeHttpRequestsShouldFailIfMediaTypeIsVideoAndMimesListIsEmpty() {
        //given
        adapterRequest = AdapterRequest.of(ADAPTER, singletonList(
                givenAdUnitBidCustomizable(builder -> builder
                        .adUnitCode("adUnitCode1")
                        .mediaTypes(singleton(MediaType.video))
                        .video(Video.builder()
                                .mimes(emptyList())
                                .build()))));

        // when and then
        assertThatThrownBy(() -> adapter.makeHttpRequests(adapterRequest, preBidRequestContext))
                .isExactlyInstanceOf(PreBidException.class)
                .hasMessage("Invalid AdUnit: VIDEO media type with no video data");
    }

    @Test
    public void makeHttpRequestsShouldReturnRequestsWithExpectedFields() {
        // given
        adapterRequest = givenBidderCustomizable(
                builder -> builder
                        .bidderCode(ADAPTER)
                        .adUnitCode("adUnitCode1")
                        .instl(1)
                        .topframe(1)
                        .sizes(singletonList(Format.builder().w(300).h(250).build()))
                        .params(mapper.valueToTree(PubmaticParams.of("publisherID", "slot42@200x150:zzz"))));

        preBidRequestContext = givenPreBidRequestContextCustomizable(
                builder -> builder
                        .referer("http://www.example.com")
                        .domain("example.com")
                        .ip("192.168.144.1")
                        .ua("userAgent"),
                builder -> builder
                        .timeoutMillis(1500L)
                        .tid("tid")
        );

        given(uidsCookie.uidFrom(eq(ADAPTER))).willReturn("buyerUid");

        // when
        final List<HttpRequest> httpRequests = adapter.makeHttpRequests(adapterRequest, preBidRequestContext);

        // then
        assertThat(httpRequests).hasSize(1)
                .extracting(HttpRequest::getBidRequest)
                .containsOnly(BidRequest.builder()
                        .id("tid")
                        .at(1)
                        .tmax(1500L)
                        .imp(singletonList(Imp.builder()
                                .id("adUnitCode1")
                                .instl(1)
                                .tagid("slot42")
                                .banner(Banner.builder()
                                        .w(200)
                                        .h(150)
                                        .topframe(1)
                                        .build())
                                .build()))
                        .site(Site.builder()
                                .domain("example.com")
                                .page("http://www.example.com")
                                .publisher(Publisher.builder().id("publisherID").domain("example.com").build())
                                .build())
                        .device(Device.builder()
                                .ua("userAgent")
                                .ip("192.168.144.1")
                                .build())
                        .user(User.builder()
                                .buyeruid("buyerUid")
                                .build())
                        .source(Source.builder()
                                .fd(1)
                                .tid("tid")
                                .build())
                        .build());
    }

    @Test
    public void makeHttpRequestsShouldReturnRequestsWithAppFromPreBidRequest() {
        // given
        preBidRequestContext = givenPreBidRequestContextCustomizable(identity(), builder -> builder
                .app(App.builder().id("appId").build()).user(User.builder().build()));

        // when
        final List<HttpRequest> httpRequests = adapter.makeHttpRequests(adapterRequest, preBidRequestContext);

        // then
        assertThat(httpRequests).hasSize(1)
                .extracting(r -> r.getBidRequest().getApp().getId())
                .containsOnly("appId");
    }

    @Test
    public void makeHttpRequestsShouldReturnRequestsWithUserFromPreBidRequestIfAppPresent() {
        // given
        preBidRequestContext = givenPreBidRequestContextCustomizable(identity(), builder -> builder
                .app(App.builder().build())
                .user(User.builder().buyeruid("buyerUid").build()));

        given(uidsCookie.uidFrom(eq(ADAPTER))).willReturn("buyerUidFromCookie");

        // when
        final List<HttpRequest> httpRequests = adapter.makeHttpRequests(adapterRequest, preBidRequestContext);

        // then
        assertThat(httpRequests).hasSize(1)
                .extracting(r -> r.getBidRequest().getUser())
                .containsOnly(User.builder().buyeruid("buyerUid").build());
    }

    @Test
    public void makeHttpRequestsShouldReturnListWithOneRequestIfAdUnitContainsBannerAndVideoMediaTypes() {
        //given
        adapterRequest = AdapterRequest.of(ADAPTER, singletonList(
                givenAdUnitBidCustomizable(builder -> builder
                        .mediaTypes(EnumSet.of(MediaType.video, MediaType.banner))
                        .video(Video.builder()
                                .mimes(singletonList("Mime1"))
                                .playbackMethod(1)
                                .build()))));

        preBidRequestContext = givenPreBidRequestContextCustomizable(identity(), identity());

        // when
        final List<HttpRequest> httpRequests = adapter.makeHttpRequests(adapterRequest, preBidRequestContext);

        // then
        assertThat(httpRequests).hasSize(1)
                .flatExtracting(r -> r.getBidRequest().getImp())
                .containsOnly(
                        Imp.builder()
                                .video(com.iab.openrtb.request.Video.builder().w(480).h(320)
                                        .mimes(singletonList("Mime1")).playbackmethod(singletonList(1)).build())
                                .build(),
                        Imp.builder()
                                .banner(Banner.builder().w(300).h(250).build())
                                .tagid("slot1")
                                .build()
                );
    }

    @Test
    public void makeHttpRequestsShouldReturnListWithOneRequestIfMultipleAdUnitsInPreBidRequest() {
        // given
        adapterRequest = AdapterRequest.of(ADAPTER, asList(
                givenAdUnitBidCustomizable(builder -> builder.adUnitCode("adUnitCode1")),
                givenAdUnitBidCustomizable(builder -> builder.adUnitCode("adUnitCode2"))));

        // when
        final List<HttpRequest> httpRequests = adapter.makeHttpRequests(adapterRequest, preBidRequestContext);

        // then
        assertThat(httpRequests).hasSize(1)
                .flatExtracting(r -> r.getBidRequest().getImp()).hasSize(2)
                .extracting(Imp::getId).containsOnly("adUnitCode1", "adUnitCode2");
    }

    @Test
    public void extractBidsShouldFailIfBidImpIdDoesNotMatchAdUnitCode() {
        // given
        adapterRequest = givenBidderCustomizable(builder -> builder.adUnitCode("adUnitCode"));

        exchangeCall = givenExchangeCallCustomizable(identity(),
                b -> b.seatbid(singletonList(SeatBid.builder()
                        .bid(singletonList(Bid.builder().impid("anotherAdUnitCode").build()))
                        .build())));

        // when and then
        assertThatThrownBy(() -> adapter.extractBids(adapterRequest, exchangeCall))
                .isExactlyInstanceOf(PreBidException.class)
                .hasMessage("Unknown ad unit code 'anotherAdUnitCode'");
    }

    @Test
    public void extractBidsShouldReturnBidBuildersWithExpectedFields() {
        // given
        adapterRequest = givenBidderCustomizable(
                builder -> builder.bidderCode(ADAPTER).bidId("bidId").adUnitCode("adUnitCode"));

        exchangeCall = givenExchangeCallCustomizable(
                bidRequestBuilder -> bidRequestBuilder.imp(singletonList(Imp.builder().id("adUnitCode").build())),
                bidResponseBuilder -> bidResponseBuilder.id("bidResponseId")
                        .seatbid(singletonList(SeatBid.builder()
                                .seat("seatId")
                                .bid(singletonList(Bid.builder()
                                        .impid("adUnitCode")
                                        .price(new BigDecimal("8.43"))
                                        .adm("adm")
                                        .crid("crid")
                                        .w(300)
                                        .h(250)
                                        .dealid("dealId")
                                        .build()))
                                .build())));

        // when
        final List<org.prebid.server.proto.response.Bid> bids =
                adapter.extractBids(adapterRequest, exchangeCall).stream()
                        .map(org.prebid.server.proto.response.Bid.BidBuilder::build).collect(Collectors.toList());

        // then
        assertThat(bids)
                .containsExactly(org.prebid.server.proto.response.Bid.builder()
                        .code("adUnitCode")
                        .price(new BigDecimal("8.43"))
                        .adm("adm")
                        .creativeId("crid")
                        .width(300)
                        .height(250)
                        .bidder(ADAPTER)
                        .bidId("bidId")
                        .dealId("dealId")
                        .build());
    }

    @Test
    public void extractBidsShouldReturnEmptyBidsIfEmptyOrNullBidResponse() {
        // given
        adapterRequest = givenBidderCustomizable(identity());

        exchangeCall = givenExchangeCallCustomizable(identity(), br -> br.seatbid(null));

        // when and then
        Assertions.assertThat(adapter.extractBids(adapterRequest, exchangeCall)).isEmpty();
        Assertions.assertThat(adapter.extractBids(adapterRequest, ExchangeCall.empty(null))).isEmpty();
    }

    @Test
    public void extractBidsShouldReturnMultipleBidBuildersIfMultipleAdUnitsInPreBidRequestAndBidsInResponse() {
        // given
        adapterRequest = AdapterRequest.of(ADAPTER, asList(
                givenAdUnitBidCustomizable(builder -> builder.adUnitCode("adUnitCode1")),
                givenAdUnitBidCustomizable(builder -> builder.adUnitCode("adUnitCode2"))));

        exchangeCall = givenExchangeCallCustomizable(identity(),
                bidResponseBuilder -> bidResponseBuilder.id("bidResponseId")
                        .seatbid(singletonList(SeatBid.builder()
                                .seat("seatId")
                                .bid(asList(Bid.builder().impid("adUnitCode1").build(),
                                        Bid.builder().impid("adUnitCode2").build()))
                                .build())));

        // when
        final List<org.prebid.server.proto.response.Bid> bids =
                adapter.extractBids(adapterRequest, exchangeCall).stream()
                        .map(org.prebid.server.proto.response.Bid.BidBuilder::build).collect(Collectors.toList());

        // then
        assertThat(bids).hasSize(2)
                .extracting(org.prebid.server.proto.response.Bid::getCode)
                .containsOnly("adUnitCode1", "adUnitCode2");
    }

    private static AdapterRequest givenBidderCustomizable(
            Function<AdUnitBid.AdUnitBidBuilder, AdUnitBid.AdUnitBidBuilder> adUnitBidBuilderCustomizer) {
        return AdapterRequest.of(ADAPTER, singletonList(givenAdUnitBidCustomizable(adUnitBidBuilderCustomizer)));
    }

    private static AdUnitBid givenAdUnitBidCustomizable(
            Function<AdUnitBid.AdUnitBidBuilder, AdUnitBid.AdUnitBidBuilder> adUnitBidBuilderCustomizer) {
        final AdUnitBid.AdUnitBidBuilder adUnitBidBuilderMinimal = AdUnitBid.builder()
                .sizes(singletonList(Format.builder().w(480).h(320).build()))
                .params(mapper.valueToTree(PubmaticParams.of("publisherId1", "slot1@300x250:zzz")))
                .mediaTypes(singleton(MediaType.banner));

        final AdUnitBid.AdUnitBidBuilder adUnitBidBuilderCustomized = adUnitBidBuilderCustomizer
                .apply(adUnitBidBuilderMinimal);

        return adUnitBidBuilderCustomized.build();
    }

    private PreBidRequestContext givenPreBidRequestContextCustomizable(
            Function<PreBidRequestContext.PreBidRequestContextBuilder, PreBidRequestContext
                    .PreBidRequestContextBuilder> preBidRequestContextBuilderCustomizer,
            Function<PreBidRequest.PreBidRequestBuilder, PreBidRequest.PreBidRequestBuilder>
                    preBidRequestBuilderCustomizer) {

        final PreBidRequest.PreBidRequestBuilder preBidRequestBuilderMinimal = PreBidRequest.builder()
                .accountId("accountId");
        final PreBidRequest preBidRequest = preBidRequestBuilderCustomizer.apply(preBidRequestBuilderMinimal).build();

        final PreBidRequestContext.PreBidRequestContextBuilder preBidRequestContextBuilderMinimal =
                PreBidRequestContext.builder()
                        .preBidRequest(preBidRequest)
                        .uidsCookie(uidsCookie);
        return preBidRequestContextBuilderCustomizer.apply(preBidRequestContextBuilderMinimal).build();
    }

    private static ExchangeCall givenExchangeCallCustomizable(
            Function<BidRequest.BidRequestBuilder, BidRequest.BidRequestBuilder> bidRequestBuilderCustomizer,
            Function<BidResponse.BidResponseBuilder, BidResponse.BidResponseBuilder> bidResponseBuilderCustomizer) {

        final BidRequest.BidRequestBuilder bidRequestBuilderMinimal = BidRequest.builder();
        final BidRequest bidRequest = bidRequestBuilderCustomizer.apply(bidRequestBuilderMinimal).build();

        final BidResponse.BidResponseBuilder bidResponseBuilderMinimal = BidResponse.builder();
        final BidResponse bidResponse = bidResponseBuilderCustomizer.apply(bidResponseBuilderMinimal).build();

        return ExchangeCall.success(bidRequest, bidResponse, BidderDebug.builder().build());
    }
}