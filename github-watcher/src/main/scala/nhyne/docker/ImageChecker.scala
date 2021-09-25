package nhyne.docker

object ImageChecker {

  /*  Have two options for polling images via AWS API
   *    - First is to use the ListTagsForResource endpoint which takes the ARN of a registry.
   *        We then search through it ourselves to find if the image tag exists.
   *        This can be expensive on our side to search (especially with thousands/millions of tags)
   *        https://docs.aws.amazon.com/AmazonECR/latest/APIReference/API_ListTagsForResource.html
   *    - Second is to use the DescribeImages endpoint which takes a registry and an Image tag
   *        If we get a 200 code back then we know the image exists.
   *        This will be cheaper on our CPU but we will make a lot more API calls to AWS (these are finite).
   *
   *  General idea for this Service will be to check for an images' existence via one of the above APIs
   *    and retry on an exponential backoff until some length of time has occurred.
   *  If we fail overall to get the image then we will comment on the PR and possibly delete the namespace?
   */
}
