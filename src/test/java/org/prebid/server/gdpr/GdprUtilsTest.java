package org.prebid.server.gdpr;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.Regs;
import com.iab.openrtb.request.User;
import org.junit.Test;
import org.prebid.server.VertxTest;
import org.prebid.server.proto.openrtb.ext.request.ExtRegs;
import org.prebid.server.proto.openrtb.ext.request.ExtUser;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

public class GdprUtilsTest extends VertxTest {

    @Test
    public void gdprFromShouldReturnEmptyValueWhenRegsIsNull() {
        // given and when
        final String gdpr = GdprUtils.gdprFrom(null, mapper);

        // then
        assertThat(gdpr).isEmpty();
    }

    @Test
    public void gdprFromShouldReturnEmptyValueWhenRegsExtIsNull() {
        // given and when
        final String gdpr = GdprUtils.gdprFrom(Regs.of(null, null), mapper);

        // then
        assertThat(gdpr).isEmpty();
    }

    @Test
    public void gdprFromShouldReturnEmptyValueWhenRegExtIsNotValidJson() throws IOException {
        // given and when
        final String gdpr = GdprUtils.gdprFrom(
                Regs.of(null, (ObjectNode) mapper.readTree("{\"gdpr\": \"gdpr\"}")), mapper);

        // then
        assertThat(gdpr).isEmpty();
    }

    @Test
    public void gdprFromShouldReturnEmptyValueWhenRegsExtGdprIsNoEqualsToOneOrZero() {
        // given and when
        final String gdpr = GdprUtils.gdprFrom(
                Regs.of(null, mapper.valueToTree(ExtRegs.of(2))), mapper);

        // then
        assertThat(gdpr).isEmpty();
    }

    @Test
    public void gdprFromShouldReturnOne() {
        // given and when
        final String gdpr = GdprUtils.gdprFrom(
                Regs.of(null, mapper.valueToTree(ExtRegs.of(1))), mapper);

        // then
        assertThat(gdpr).isEqualTo("1");
    }

    @Test
    public void gdprFromShouldReturnZero() {
        // given and when
        final String gdpr = GdprUtils.gdprFrom(
                Regs.of(null, mapper.valueToTree(ExtRegs.of(0))), mapper);

        // then
        assertThat(gdpr).isEqualTo("0");
    }

    @Test
    public void gdprConsentFromShouldReturnEmptyValueWhenExtUserIsNull() {
        // given and when
        final String consent = GdprUtils.gdprConsentFrom(null, mapper);

        // then
        assertThat(consent).isEmpty();
    }

    @Test
    public void gdprConsentFromShouldReturnEmptyValueWhenConsentIsNull() {
        // given and when
        final String consent = GdprUtils.gdprConsentFrom(
                User.builder().ext(mapper.valueToTree(ExtUser.builder().build())).build(), mapper);

        // then
        assertThat(consent).isEmpty();
    }

    @Test
    public void gdprConsentFromShouldReturnConsent() {
        // given and when
        final String consent = GdprUtils.gdprConsentFrom(
                User.builder().ext(mapper.valueToTree(ExtUser.builder().consent("consent").build())).build(), mapper);

        // then
        assertThat(consent).isEqualTo("consent");
    }
}
