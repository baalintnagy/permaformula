package org.permmath.url;

import java.util.List;

import javax.validation.constraints.NotBlank;

import org.permmath.validation.ValidImageWidth;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@EqualsAndHashCode
@ToString
public class ImageDescriptor {

    @NonNull
    private String forumla;

    @ValidImageWidth
    private Integer imageWidth;
    private List<@NotBlank String> styleRefs;
    private String style;
}
