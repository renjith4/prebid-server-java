package org.rtb.vexing.auction.model;

import lombok.AllArgsConstructor;
import lombok.Value;

@AllArgsConstructor(staticName = "of")
@Value
public final class AmpRequest {

    String tagId;
}