package org.permmath.url;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

import org.permmath.validation.ValidImageWidth;

import com.fasterxml.jackson.annotation.JsonInclude;

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
@JsonInclude(NON_EMPTY)
public class ImageDescriptor {

    @NonNull
    private String forumla;

    @ValidImageWidth
    private Integer imageWidth;

    private List<@NotBlank String> styleRefs;

    private String style;
}
