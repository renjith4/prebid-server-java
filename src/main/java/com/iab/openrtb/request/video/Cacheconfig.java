package com.iab.openrtb.request.video;

import lombok.AllArgsConstructor;
import lombok.Value;

@AllArgsConstructor(staticName = "of")
@Value
public class Cacheconfig {

    Integer ttl;
}

